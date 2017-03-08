package openllet.core.taxonomy;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import openllet.aterm.ATermAppl;
import openllet.aterm.ATermList;
import openllet.atom.OpenError;
import openllet.core.KnowledgeBase;
import openllet.core.OpenlletOptions;
import openllet.core.boxes.abox.Individual;
import openllet.core.boxes.rbox.Role;
import openllet.core.boxes.tbox.TBox;
import openllet.core.boxes.tbox.impl.Unfolding;
import openllet.core.exceptions.InternalReasonerException;
import openllet.core.utils.ATermUtils;
import openllet.core.utils.CollectionUtils;
import openllet.core.utils.Comparators;
import openllet.core.utils.MemUtils;
import openllet.core.utils.SetUtils;
import openllet.core.utils.TaxonomyUtils;
import openllet.core.utils.Timer;
import openllet.core.utils.progress.ProgressMonitor;
import openllet.shared.tools.Log;

/**
 * Instance of the builder.
 *
 * @since 2.6.1
 */
public class CDOptimizedTaxonomyBuilderProb implements TaxonomyBuilder
{
	protected static Logger _logger = Log.getLogger(TaxonomyImpl.class);

	private static enum Propogate
	{
		UP, DOWN, NONE
	}

	private static enum ConceptFlag
	{
		COMPLETELY_DEFINED, PRIMITIVE, NONPRIMITIVE, NONPRIMITIVE_TA, OTHER
	}

	private static final Set<ConceptFlag> PHASE1_FLAGS = EnumSet.of(ConceptFlag.COMPLETELY_DEFINED, ConceptFlag.PRIMITIVE, ConceptFlag.OTHER);

	private final Map<ATermAppl, Set<ATermAppl>> _toldDisjoints = CollectionUtils.makeIdentityMap();

	private final Map<ATermAppl, ATermList> _unionClasses = CollectionUtils.makeIdentityMap();

	private final KnowledgeBase _kb;

	private final List<TaxonomyNode<ATermAppl>> _markedNodes = CollectionUtils.makeList();

	private final Map<ATermAppl, ConceptFlag> _conceptFlags = CollectionUtils.makeIdentityMap();

	private volatile ProgressMonitor _monitor;

	private final Collection<ATermAppl> _classes;

	private final Taxonomy<ATermAppl> _toldTaxonomy;

	private final Taxonomy<ATermAppl> _taxonomyImpl;

	private final DefinitionOrder _definitionOrder;

	public CDOptimizedTaxonomyBuilderProb(final KnowledgeBase kb, final Optional<ProgressMonitor> monitor)
	{
		_kb = kb;
		_monitor = monitor.orElse(OpenlletOptions.USE_CLASSIFICATION_MONITOR.create());
		_classes = _kb.getClasses();
		_definitionOrder = new JGraphBasedDefinitionOrder(_kb, Comparators.termComparator);
		_taxonomyImpl = new TaxonomyImpl<>(null, ATermUtils.TOP, ATermUtils.BOTTOM);
		_toldTaxonomy = new TaxonomyImpl<>(_classes, ATermUtils.TOP, ATermUtils.BOTTOM);

		classify();
	}

	@Deprecated
	@Override
	public void setProgressMonitor(final ProgressMonitor monitor)
	{
		_monitor = monitor;
	}

	@Override
	public Taxonomy<ATermAppl> getTaxonomy()
	{
		return _taxonomyImpl;
	}

	@Override
	public Taxonomy<ATermAppl> getToldTaxonomy()
	{
		return _toldTaxonomy;
	}

	@Override
	public Map<ATermAppl, Set<ATermAppl>> getToldDisjoints()
	{
		return _toldDisjoints;
	}

	/**
	 * Classify the KB.
	 */
	@Override
	public boolean classify()
	{
		int classCount = _classes.size();
		if (!_classes.contains(ATermUtils.TOP))
			classCount++;
		if (!_classes.contains(ATermUtils.BOTTOM))
			classCount++;

		_monitor.setProgressTitle("Classifying");
		_monitor.setProgressLength(classCount);
		_monitor.taskStarted();

		if (_classes.isEmpty())
			return true;

		prepare();

		_logger.fine("Starting classification...");

		final List<ATermAppl> phase1 = new ArrayList<>();
		final List<ATermAppl> phase2 = new ArrayList<>();
		for (final ATermAppl c : _definitionOrder)
			if (PHASE1_FLAGS.contains(_conceptFlags.get(c)))
				phase1.add(c);
			else
				phase2.add(c);

		boolean completed = true;

		completed = completed && classify(phase1, /* requireTopSearch = */false);
		completed = completed && classify(phase2, /* requireTopSearch = */true);

		_monitor.taskFinished();

		_logger.fine(() -> "Satisfiability Count: " + (_kb.getABox().getStats()._satisfiabilityCount - 2 * _kb.getClasses().size()));

		_taxonomyImpl.assertValid();

		if (_logger.isLoggable(Level.FINER))
			_logger.finer("Tax size : " + _taxonomyImpl.getNodes().size() + "\n"//
					+ "Tax depth: " + _taxonomyImpl.getDepth() + "\n"//
					+ "Branching: " + (double) _taxonomyImpl.getTotalBranching() / _taxonomyImpl.getNodes().size() + "\n"//
					+ "Leaf size: " + _taxonomyImpl.getBottomNode().getSupers().size());

		return completed;
	}

	protected void checkClassifyTimer()
	{
		final Timer timer = _kb.getTimers().getTimer("classify"); // TODO : coordination of the name of the classifier with the KB.
		if (null != timer) // It come from outside the taxonomy builder.
			timer.check();
	}

	protected void checkRealizeTimer()
	{
		final Timer timer = _kb.getTimers().getTimer("realize"); // TODO : coordination of the name of the classifier with the KB.
		if (null != timer) // It come from outside the taxonomy builder.
			timer.check();
	}

	protected boolean classify(final List<ATermAppl> phase, final boolean requireTopSearch)
	{
		for (final ATermAppl c : phase)
		{
			_logger.fine(() -> "Classify (" + _taxonomyImpl.getNodes().size() + ") " + format(c) + "...");

			classify(c, requireTopSearch);
			_monitor.incrementProgress();
			checkClassifyTimer();

			if (_monitor.isCanceled())
				return false;
		}

		return true;
	}

	private void prepare()
	{
		computeToldInformation();
		computeConceptFlags();
	}

	protected void reset()
	{
		_kb.prepare();

		_classes.clear();

		_toldDisjoints.clear();
		_unionClasses.clear();
		_markedNodes.clear();

		_conceptFlags.clear();
	}

	/**
	 * compute told subsumers for each concept
	 */
	private void computeToldInformation()
	{
		final TBox tbox = _kb.getTBox();
		for (final ATermAppl axiom : tbox.getAxioms())
		{
			final ATermAppl c1 = (ATermAppl) axiom.getArgument(0);
			final ATermAppl c2 = (ATermAppl) axiom.getArgument(1);

			final boolean equivalent = axiom.getAFun().equals(ATermUtils.EQCLASSFUN);
			final Set<ATermAppl> explanation = tbox.getAxiomExplanation(axiom);

			final boolean reverseArgs = !ATermUtils.isPrimitive(c1) && ATermUtils.isPrimitive(c2);
			if (equivalent && reverseArgs)
				addToldRelation(c2, c1, equivalent, explanation);
			else
				addToldRelation(c1, c2, equivalent, explanation);
		}

		// additional step for union classes. for example, if we have C = or(A, B)
		// and both A and B subclass of D then we can conclude C is also subclass of D
		for (final ATermAppl c : _unionClasses.keySet())
		{

			final List<ATermAppl> list = new ArrayList<>();
			for (ATermList disj = _unionClasses.get(c); !disj.isEmpty(); disj = disj.getNext())
				list.add((ATermAppl) disj.getFirst());
			final List<ATermAppl> lca = _toldTaxonomy.computeLCA(list);

			for (final ATermAppl d : lca)
				addToldSubsumer(c, d);
		}
	}

	private void computeConceptFlags()
	{
		/*
		 * Use RBox domain axioms to mark some concepts as complex
		 */
		for (final Role r : _kb.getRBox().getRoles().values())
			for (final ATermAppl c : r.getDomains())
				if (ATermUtils.isPrimitive(c))
					_conceptFlags.put(c, ConceptFlag.OTHER);
				else
					if (ATermUtils.isAnd(c))
					{
						ATermList list = (ATermList) c.getArgument(0);
						for (; !list.isEmpty(); list = list.getNext())
						{
							final ATermAppl d = (ATermAppl) list.getFirst();
							if (ATermUtils.isPrimitive(d))
								_conceptFlags.put(d, ConceptFlag.OTHER);
						}
					}
					else
						if (ATermUtils.isNot(c) && ATermUtils.isAnd((ATermAppl) c.getArgument(0)))
						{
							ATermList list = (ATermList) ((ATermAppl) c.getArgument(0)).getArgument(0);
							for (; !list.isEmpty(); list = list.getNext())
							{
								final ATermAppl d = (ATermAppl) list.getFirst();
								if (ATermUtils.isNegatedPrimitive(d))
									_conceptFlags.put((ATermAppl) d.getArgument(0), ConceptFlag.OTHER);
							}
						}

		/*
		 * Iterate over the post-absorption unfolded class descriptions to set
		 * concept flags The iteration needs to be over classes to include
		 * orphans
		 */
		final TBox tbox = _kb.getTBox();
		for (final ATermAppl c : _definitionOrder)
		{
			final Iterator<Unfolding> unfoldingList = _kb.getTBox().unfold(c);

			if (!tbox.isPrimitive(c) || _definitionOrder.isCyclic(c) || _toldTaxonomy.getAllEquivalents(c).size() > 1)
			{
				_conceptFlags.put(c, ConceptFlag.NONPRIMITIVE);
				while (unfoldingList.hasNext())
				{
					final Unfolding unf = unfoldingList.next();
					for (final ATermAppl d : ATermUtils.findPrimitives(unf.getResult()))
					{
						final ConceptFlag current = _conceptFlags.get(d);
						if (current == null || current == ConceptFlag.COMPLETELY_DEFINED)
							_conceptFlags.put(d, ConceptFlag.PRIMITIVE);
					}
				}
				continue;
			}

			boolean flagged = false;
			for (final ATermAppl sup : _toldTaxonomy.getFlattenedSupers(c, /* direct = */true))
			{
				final ConceptFlag supFlag = _conceptFlags.get(sup);
				if (supFlag == ConceptFlag.NONPRIMITIVE || supFlag == ConceptFlag.NONPRIMITIVE_TA)
				{
					_conceptFlags.put(c, ConceptFlag.NONPRIMITIVE_TA);
					flagged = true;
					break;
				}
			}
			if (flagged)
				continue;

			/*
			 * The concept may have appeared in the definition of a
			 * non-primitive or, it may already have an 'OTHER' flag.
			 */
			if (_conceptFlags.get(c) != null)
				continue;

			_conceptFlags.put(c, isCDDesc(unfoldingList) ? ConceptFlag.COMPLETELY_DEFINED : ConceptFlag.PRIMITIVE);
		}
	}

	private void clearMarks()
	{
		_markedNodes.stream().filter(n -> n != null).forEach(TaxonomyNode::resetMark);
		_markedNodes.clear();
	}

	private boolean isCDDesc(final Iterator<Unfolding> unfoldingList)
	{
		while (unfoldingList.hasNext())
		{
			final Unfolding unf = unfoldingList.next();
			if (!isCDDesc(unf.getResult()))
				return false;
		}

		return true;
	}

	private boolean isCDDesc(final ATermAppl desc)
	{
		if (desc == null)
			return true;

		if (ATermUtils.isPrimitive(desc))
			return true;

		if (ATermUtils.isAllValues(desc))
			return true;

		if (ATermUtils.isAnd(desc))
		{
			boolean allCDConj = true;
			final ATermList conj = (ATermList) desc.getArgument(0);
			for (ATermList subConj = conj; allCDConj && !subConj.isEmpty(); subConj = subConj.getNext())
			{
				final ATermAppl ci = (ATermAppl) subConj.getFirst();
				allCDConj = isCDDesc(ci);
			}
			return allCDConj;
		}

		if (ATermUtils.isNot(desc))
		{
			final ATermAppl negd = (ATermAppl) desc.getArgument(0);

			if (ATermUtils.isPrimitive(negd))
				return true;

		}

		return false;
	}

	private void addToldRelation(final ATermAppl c, final ATermAppl d, final boolean equivalent, final Set<ATermAppl> explanation)
	{

		if (!equivalent && (c == ATermUtils.BOTTOM || d == ATermUtils.TOP))
			return;

		if (!ATermUtils.isPrimitive(c))
		{
			if (c.getAFun().equals(ATermUtils.ORFUN))
			{
				final ATermList list = (ATermList) c.getArgument(0);
				for (ATermList disj = list; !disj.isEmpty(); disj = disj.getNext())
				{
					final ATermAppl e = (ATermAppl) disj.getFirst();
					addToldRelation(e, d, false, explanation);
				}
			}
		}
		else
			if (ATermUtils.isPrimitive(d))
			{
				if (ATermUtils.isBnode(d))
					return;

				if (!equivalent)
				{
					if (_logger.isLoggable(Level.FINER))
						_logger.finer("Preclassify (1) " + format(c) + " " + format(d));

					addToldSubsumer(c, d, explanation);
				}
				else
				{
					if (_logger.isLoggable(Level.FINER))
						_logger.finer("Preclassify (2) " + format(c) + " " + format(d));

					addToldEquivalent(c, d);
				}
			}
			else
				if (d.getAFun().equals(ATermUtils.ANDFUN))
					for (ATermList conj = (ATermList) d.getArgument(0); !conj.isEmpty(); conj = conj.getNext())
					{
						final ATermAppl e = (ATermAppl) conj.getFirst();
						addToldRelation(c, e, false, explanation);
					}
				else
					if (d.getAFun().equals(ATermUtils.ORFUN))
					{
						boolean allPrimitive = true;

						final ATermList list = (ATermList) d.getArgument(0);
						for (ATermList disj = list; !disj.isEmpty(); disj = disj.getNext())
						{
							final ATermAppl e = (ATermAppl) disj.getFirst();
							if (ATermUtils.isPrimitive(e))
							{
								if (equivalent)
								{
									if (_logger.isLoggable(Level.FINER))
										_logger.finer("Preclassify (3) " + format(c) + " " + format(e));

									addToldSubsumer(e, c);
								}
							}
							else
								allPrimitive = false;
						}

						if (allPrimitive)
							_unionClasses.put(c, list);
					}
					else
						if (d.equals(ATermUtils.BOTTOM))
						{
							_logger.finer(() -> "Preclassify (4) " + format(c) + " BOTTOM");
							addToldEquivalent(c, ATermUtils.BOTTOM);
						}
						else
							if (d.getAFun().equals(ATermUtils.NOTFUN))
							{
								// handle case sub(a, not(b)) which implies sub[a][b] is false
								final ATermAppl negation = (ATermAppl) d.getArgument(0);
								if (ATermUtils.isPrimitive(negation))
								{
									_logger.finer(() -> "Preclassify (5) " + format(c) + " not " + format(negation));

									addToldDisjoint(c, negation);
									addToldDisjoint(negation, c);
								}
							}
	}

	private void addToldEquivalent(final ATermAppl c, final ATermAppl d)
	{
		if (c.equals(d))
			return;

		final TaxonomyNode<ATermAppl> cNode = _toldTaxonomy.getNode(c);
		final TaxonomyNode<ATermAppl> dNode = _toldTaxonomy.getNode(d);

		_toldTaxonomy.merge(cNode, dNode);

		TaxonomyUtils.clearSuperExplanation(_toldTaxonomy, c);
	}

	private void addToldSubsumer(final ATermAppl c, final ATermAppl d)
	{
		addToldSubsumer(c, d, null);
	}

	private void addToldSubsumer(final ATermAppl c, final ATermAppl d, final Set<ATermAppl> explanation)
	{
		final TaxonomyNode<ATermAppl> cNode = _toldTaxonomy.getNode(c);
		final TaxonomyNode<ATermAppl> dNode = _toldTaxonomy.getNode(d);

		if (cNode == null)
			throw new InternalReasonerException(c + " is not in the definition _order");

		if (dNode == null)
			throw new InternalReasonerException(d + " is not in the definition _order");

		if (cNode.equals(dNode))
			return;

		if (cNode.equals(_toldTaxonomy.getTop()))
		{
			_toldTaxonomy.merge(cNode, dNode);

			TaxonomyUtils.clearSuperExplanation(_toldTaxonomy, c);
		}
		else
		{
			_toldTaxonomy.addSuper(c, d);

			_toldTaxonomy.removeCycles(cNode);

			if (cNode.getEquivalents().size() > 1)
				TaxonomyUtils.clearSuperExplanation(_toldTaxonomy, c);
			else
				if (explanation != null && !explanation.isEmpty())
					TaxonomyUtils.addSuperExplanation(_toldTaxonomy, c, d, explanation);
		}
	}

	private void addToldDisjoint(final ATermAppl c, final ATermAppl d)
	{
		Set<ATermAppl> disjoints = _toldDisjoints.get(c);
		if (disjoints == null)
		{
			disjoints = new HashSet<>();
			_toldDisjoints.put(c, disjoints);
		}
		disjoints.add(d);
	}

	private void markToldSubsumers(final ATermAppl c)
	{
		final TaxonomyNode<ATermAppl> node = _taxonomyImpl.getNode(c);
		if (node != null)
		{
			final boolean newMark = mark(node, true, Propogate.UP);
			if (!newMark)
				return;
		}
		else
			if (_logger.isLoggable(Level.FINE) && _markedNodes.size() > 2)
				_logger.warning("Told subsumer " + c + " is not classified yet");

		if (_toldTaxonomy.contains(c)) // TODO just getting direct supers and letting recursion handle rest might be more efficient
			for (final ATermAppl sup : _toldTaxonomy.getFlattenedSupers(c, /* direct = */true))
				markToldSubsumers(sup);
	}

	private void markToldSubsumeds(final ATermAppl c, final boolean b)
	{
		final TaxonomyNode<ATermAppl> node = _taxonomyImpl.getNode(c);
		if (node != null)
		{
			final boolean newMark = mark(node, b, Propogate.DOWN);
			if (!newMark)
				return;
		}

		if (_toldTaxonomy.contains(c))
			for (final ATermAppl sub : _toldTaxonomy.getFlattenedSubs(c, /* direct = */true))
				markToldSubsumeds(sub, b);
	}

	private void markToldDisjoints(final Collection<ATermAppl> inputc, final boolean topSearch)
	{

		final Set<ATermAppl> cset = new HashSet<>();
		cset.addAll(inputc);

		for (final ATermAppl c : inputc)
		{
			if (_taxonomyImpl.contains(c))
				cset.addAll(_taxonomyImpl.getFlattenedSupers(c, /* direct = */false));

			if (_toldTaxonomy.contains(c))
				cset.addAll(_toldTaxonomy.getFlattenedSupers(c, /* direct = */false));
		}

		final Set<ATermAppl> disjoints = new HashSet<>();
		for (final ATermAppl a : cset)
		{
			final Set<ATermAppl> disj = _toldDisjoints.get(a);
			if (disj != null)
				disjoints.addAll(disj);
		}

		if (topSearch)
			for (final ATermAppl d : disjoints)
			{
				final TaxonomyNode<ATermAppl> node = _taxonomyImpl.getNode(d);
				if (node != null)
					mark(node, false, Propogate.NONE);
			}
		else
			for (final ATermAppl d : disjoints)
				markToldSubsumeds(d, false);
	}

	private TaxonomyNode<ATermAppl> checkSatisfiability(final ATermAppl c)
	{
		_logger.finer("Satisfiable ");

		boolean isSatisfiable = _kb.getABox().isSatisfiable(c, true);

		if (!isSatisfiable)
			_taxonomyImpl.addEquivalentNode(c, _taxonomyImpl.getBottomNode());

		if (OpenlletOptions.USE_CACHING)
		{
			_logger.finer("...negation ");

			final ATermAppl notC = ATermUtils.makeNot(c);
			isSatisfiable = _kb.getABox().isSatisfiable(notC, true);

			if (!isSatisfiable)
				_taxonomyImpl.addEquivalentNode(c, _taxonomyImpl.getTop());
		}

		return _taxonomyImpl.getNode(c);
	}

	/**
	 * Add a new concept to the already classified taxonomy
	 */
	@Override
	public void classify(final ATermAppl c)
	{
		classify(c, /* requireTopSearch = */true);
	}

	private TaxonomyNode<ATermAppl> classify(final ATermAppl c, final boolean requireTopSearch)
	{
		boolean skipTopSearch;
		boolean skipBottomSearch;

		TaxonomyNode<ATermAppl> node = _taxonomyImpl.getNode(c);
		if (node != null)
			return node;

		node = checkSatisfiability(c);
		if (node != null)
			return node;

		clearMarks();

		List<TaxonomyNode<ATermAppl>> superNodes;
		List<TaxonomyNode<ATermAppl>> subNodes;
		List<ATermAppl> subs;
		List<ATermAppl> supers;

		ConceptFlag flag = _conceptFlags.get(c);

		// FIXME: There may be a better thing to do here...
		if (flag == null)
			flag = ConceptFlag.OTHER;

		skipTopSearch = !requireTopSearch && flag == ConceptFlag.COMPLETELY_DEFINED;

		if (skipTopSearch)
		{
			superNodes = getCDSupers(c);
			skipBottomSearch = true;
		}
		else
		{
			superNodes = doTopSearch(c);
			skipBottomSearch = flag == ConceptFlag.PRIMITIVE || flag == ConceptFlag.COMPLETELY_DEFINED;
		}

		supers = new ArrayList<>();
		for (final TaxonomyNode<ATermAppl> n : superNodes)
			supers.add(n.getName());

		if (skipBottomSearch)
			subs = Collections.singletonList(ATermUtils.BOTTOM);
		else
		{
			if (superNodes.size() == 1)
			{
				final TaxonomyNode<ATermAppl> supNode = superNodes.iterator().next();

				/*
				 * if i has only one super class j and j is a subclass of i then
				 * it means i = j. There is no need to classify i since we
				 * already know everything about j
				 */
				final ATermAppl sup = supNode.getName();
				final boolean isEq = subsumes(c, sup);
				if (isEq)
				{
					_logger.finer(() -> format(c) + " = " + format(sup));
					_taxonomyImpl.addEquivalentNode(c, supNode);
					return supNode;
				}
			}

			subNodes = doBottomSearch(c, superNodes);
			subs = new ArrayList<>();
			for (final TaxonomyNode<ATermAppl> n : subNodes)
				subs.add(n.getName());
		}

		node = _taxonomyImpl.addNode(Collections.singleton(c), supers, subs, /* hidden = */
				!ATermUtils.isPrimitive(c));

		/*
		 * For told relations maintain explanations.
		 */
		final TaxonomyNode<ATermAppl> toldNode = _toldTaxonomy.getNode(c);
		if (toldNode != null)
		{
			// Add the told equivalents to the taxonomy
			final TaxonomyNode<ATermAppl> defOrder = _toldTaxonomy.getNode(c);
			for (final ATermAppl eq : defOrder.getEquivalents())
				_taxonomyImpl.addEquivalentNode(eq, node);

			for (final TaxonomyNode<ATermAppl> n : superNodes)
			{
				final Set<Set<ATermAppl>> exps = TaxonomyUtils.getSuperExplanations(_toldTaxonomy, c, n.getName());
				if (exps != null)
					for (final Set<ATermAppl> exp : exps)
						if (!exp.isEmpty())
							TaxonomyUtils.addSuperExplanation(_taxonomyImpl, c, n.getName(), exp);
			}
		}

		_logger.finer(() -> "Subsumption Count: " + _kb.getABox().getStats()._satisfiabilityCount);

		return node;
	}

	private List<TaxonomyNode<ATermAppl>> doBottomSearch(final ATermAppl c, final List<TaxonomyNode<ATermAppl>> supers)
	{
		final Set<TaxonomyNode<ATermAppl>> searchFrom;
		if (supers.size() > 1)
		{
			searchFrom = Collections.newSetFromMap(new ConcurrentHashMap<>());
			supers.parallelStream().forEach(sup -> collectLeafs(sup, searchFrom));
		}
		else
		{
			searchFrom = new HashSet<>();
			for (final TaxonomyNode<ATermAppl> sup : supers)
				collectLeafs(sup, searchFrom);
		}

		if (searchFrom.isEmpty())
			return Collections.singletonList(_taxonomyImpl.getBottomNode());

		clearMarks();

		mark(_taxonomyImpl.getTop(), false, Propogate.NONE);
		_taxonomyImpl.getBottomNode().setMark(true);
		markToldSubsumeds(c, true);
		for (final TaxonomyNode<ATermAppl> sup : supers)
			mark(sup, false, Propogate.NONE);

		_logger.finer("Bottom search...");

		final List<TaxonomyNode<ATermAppl>> subs = new ArrayList<>();
		final Set<TaxonomyNode<ATermAppl>> visited = new HashSet<>();
		for (final TaxonomyNode<ATermAppl> n : searchFrom)
			if (subsumed(n, c))
				search( /* topSearch = */false, c, n, visited, subs);

		if (subs.isEmpty())
			return Collections.singletonList(_taxonomyImpl.getBottomNode());

		return subs;
	}

	private static void collectLeafs(final TaxonomyNode<ATermAppl> node, final Collection<TaxonomyNode<ATermAppl>> leafs)
	{
		if (node.isLeaf())
		{
			leafs.add(node);
			return;
		}

		final ArrayList<TaxonomyNode<ATermAppl>> nodes = new ArrayList<>();
		nodes.add(node);

		do
		{
			final TaxonomyNode<ATermAppl> child = nodes.remove(nodes.size() - 1); // Last is remove first to avoid reallocation of the array.
			for (final TaxonomyNode<ATermAppl> loc : child.getSubs())
				(loc.isLeaf() ? leafs : nodes).add(loc);
		} while (!nodes.isEmpty());
	}

	private List<TaxonomyNode<ATermAppl>> doTopSearch(final ATermAppl c)
	{
		final List<TaxonomyNode<ATermAppl>> supers = new ArrayList<>();

		mark(_taxonomyImpl.getTop(), true, Propogate.NONE);
		_taxonomyImpl.getBottomNode().setMark(false);
		markToldSubsumers(c);
		markToldDisjoints(Collections.singleton(c), true);

		_logger.finer("Top search...");

		search(true, c, _taxonomyImpl.getTop(), new HashSet<TaxonomyNode<ATermAppl>>(), supers);

		return supers;
	}

	private List<TaxonomyNode<ATermAppl>> getCDSupers(final ATermAppl c)
	{

		/*
		 * Find all of told subsumers already classified and not redundant
		 */
		final List<TaxonomyNode<ATermAppl>> supers = new ArrayList<>();

		final TaxonomyNode<ATermAppl> toldTaxNode = _toldTaxonomy.getNode(c);

		// every class is added to told taxonomy so we cannot have null values here
		assert toldTaxNode != null;

		final Collection<TaxonomyNode<ATermAppl>> cDefs = toldTaxNode.getSupers();

		final int nTS = cDefs.size();
		if (nTS == 1)
			for (final TaxonomyNode<ATermAppl> def : cDefs)
			{
				if (def == _toldTaxonomy.getTop())
					continue;
				final TaxonomyNode<ATermAppl> parent = _taxonomyImpl.getNode(def.getName());
				if (parent == null)
				{
					_logger.warning("Possible tautological definition, assuming " + format(def.getName()) + " is equivalent to " + format(ATermUtils.TOP));
					_logger.fine(() -> "Told subsumer of " + format(c) + "  is not classified: " + format(def.getName()));
				}
				else
					supers.add(parent);
				break;
			}
		else
		{
			for (final TaxonomyNode<ATermAppl> def : cDefs)
			{
				if (def == _toldTaxonomy.getTop())
					continue;
				final TaxonomyNode<ATermAppl> candidate = _taxonomyImpl.getNode(def.getName());
				if (null == candidate)
				{
					_logger.warning("Possible tautological definition, assuming " + format(def.getName()) + " is equivalent to " + format(ATermUtils.TOP));
					_logger.fine(() -> "Told subsumer of " + format(c) + "  is not classified: " + format(def.getName()));
				}
				else
					for (final TaxonomyNode<ATermAppl> ancestor : candidate.getSupers())
						mark(ancestor, true, Propogate.UP);
			}
			for (final TaxonomyNode<ATermAppl> def : cDefs)
			{
				if (def == _toldTaxonomy.getTop())
					continue;
				final TaxonomyNode<ATermAppl> candidate = _taxonomyImpl.getNode(def.getName());
				if (!candidate.markIsDefined())
				{
					supers.add(candidate);
					_logger.finer(() -> "...completely defined by " + candidate.getName().getName());
				}
			}
		}

		if (supers.isEmpty())
			supers.add(_taxonomyImpl.getTop());

		return supers;
	}

	private Collection<TaxonomyNode<ATermAppl>> search(final boolean topSearch, final ATermAppl c, final TaxonomyNode<ATermAppl> x, final Set<TaxonomyNode<ATermAppl>> visited, final List<TaxonomyNode<ATermAppl>> result)
	{
		final List<TaxonomyNode<ATermAppl>> posSucc = new ArrayList<>();
		visited.add(x);

		final Collection<TaxonomyNode<ATermAppl>> list = topSearch ? x.getSubs() : x.getSupers();

		for (final TaxonomyNode<ATermAppl> next : list)
			if (topSearch)
			{
				if (nodeSubsumes(next, c))
					posSucc.add(next);
			}
			else
				if (subsumed(next, c))
					posSucc.add(next);

		if (posSucc.isEmpty())
			result.add(x);
		else
			for (final TaxonomyNode<ATermAppl> y : posSucc)
				if (!visited.contains(y))
					search(topSearch, c, y, visited, result);

		return result;
	}

	private boolean subCheckWithCache(final TaxonomyNode<ATermAppl> node, final ATermAppl c, final boolean topDown)
	{
		if (node.markIsDefined())
			return node.getMark();

		/*
		 * Search ancestors for marks to propogate
		 */
		final Collection<TaxonomyNode<ATermAppl>> others = topDown ? node.getSupers() : node.getSubs();

		if (others.size() > 1)
		{
			final Map<TaxonomyNode<ATermAppl>, TaxonomyNode<ATermAppl>> visited = new HashMap<>();
			Map<TaxonomyNode<ATermAppl>, TaxonomyNode<ATermAppl>> toBeVisited = new HashMap<>(); // used as a Set of Pair where the second par isn't part of the key.
			Map<TaxonomyNode<ATermAppl>, TaxonomyNode<ATermAppl>> nextVisit = new HashMap<>();

			visited.put(node, null); // Null is the stop signal for the path marking.

			for (final TaxonomyNode<ATermAppl> n : others)
				toBeVisited.put(n, node);

			// Look for "FALSE" mark in all relatives
			while (!toBeVisited.isEmpty())
			{
				for (final Entry<TaxonomyNode<ATermAppl>, TaxonomyNode<ATermAppl>> entry : toBeVisited.entrySet())
				{
					final TaxonomyNode<ATermAppl> relative = entry.getKey();

					if (!relative.markIsDefined())
					{
						final Collection<TaxonomyNode<ATermAppl>> moreRelatives = topDown ? relative.getSupers() : relative.getSubs();
						for (final TaxonomyNode<ATermAppl> n : moreRelatives)
							if (!visited.containsKey(n) && !toBeVisited.containsKey(n))
								nextVisit.put(n, relative); // XXX This side effect is another obstacle to parallel implementation here.
					}
					else
						if (!relative.getMark())
						{ // Mark the path as False
							final TaxonomyNode<ATermAppl> reachedFrom = entry.getValue();
							for (TaxonomyNode<ATermAppl> n = reachedFrom; n != null; n = visited.get(n))
								mark(n, false, Propogate.NONE);
							return false; // XXX This middle return make hard the parallel implementation here.
						}

				}
				visited.putAll(toBeVisited);

				{ // Swap - XXX swap implementation make data non 'final' so we can't we stream parallel implementation any more...
					toBeVisited.clear(); // Didn't purge the memory of the underlying array (wanted effect).
					final Map<TaxonomyNode<ATermAppl>, TaxonomyNode<ATermAppl>> tmp = nextVisit;
					nextVisit = toBeVisited;
					toBeVisited = tmp;
				}

				//				{ // Non swap XXX allow stream parallel implementation but force more operations on hash and cleaning.
				//					toBeVisited.clear();
				//					toBeVisited.putAll(nextVisit);
				//					nextVisit.clear();
				//				}

			}
		}

		// check subsumption
		final boolean calcdMark = topDown ? subsumes(node.getName(), c) : subsumes(c, node.getName());
		// mark the node appropriately
		mark(node, calcdMark, Propogate.NONE);

		return calcdMark;
	}

	private boolean nodeSubsumes(final TaxonomyNode<ATermAppl> node, final ATermAppl c)
	{
		return subCheckWithCache(node, c, true);
	}

	private boolean subsumed(final TaxonomyNode<ATermAppl> node, final ATermAppl c)
	{
		return subCheckWithCache(node, c, false);
	}

	private boolean mark(final TaxonomyNode<ATermAppl> node, final boolean value, final Propogate propogate)
	{
		if (node.getEquivalents().contains(ATermUtils.BOTTOM))
			return true;

		if (node.markIsDefined())
			if (node.getMark() != value)
				throw new OpenError("Inconsistent classification result " + node.getName() + " " + node.getMark() + " " + value);
			else
				return false;
		node.setMark(value);
		_markedNodes.add(node);

		if (propogate != Propogate.NONE)
		{
			final Collection<TaxonomyNode<ATermAppl>> others = propogate == Propogate.UP ? node.getSupers() : node.getSubs();
			for (final TaxonomyNode<ATermAppl> n : others)
				mark(n, value, propogate);
		}

		return true;
	}

	private boolean subsumes(final ATermAppl sup, final ATermAppl sub) // CPU hot spot.
	{
		return _kb.getABox().isSubClassOf(sub, sup);
	}

	private static void mark(final Set<ATermAppl> set, final Map<ATermAppl, Boolean> marked, final boolean value)
	{
		set.forEach(c -> marked.put(c, value));
	}

	/**
	 * Realize the KB by finding the instances of each class.
	 *
	 * @return boolean False if the progress monitor is canceled
	 */
	@Override
	public boolean realize()
	{
		_monitor.setProgressTitle("Realizing");

		return OpenlletOptions.REALIZE_INDIVIDUAL_AT_A_TIME ? realizeByIndividuals() : realizeByConcepts();
	}

	private boolean realizeByIndividuals()
	{
		_monitor.setProgressLength(_kb.getIndividuals().size());
		_monitor.taskStarted();

		final Iterator<Individual> i = _kb.getABox().getIndIterator();
		for (int count = 0; i.hasNext(); count++)
		{
			final Individual x = i.next();

			_monitor.incrementProgress();
			checkRealizeTimer();

			if (_monitor.isCanceled())
				return false;

			if (_logger.isLoggable(Level.FINER))
				_logger.finer(count + ") Realizing " + format(x.getName()) + " ");

			realize(x);
		}

		_monitor.taskFinished();

		return true;
	}

	@Override
	public void realize(final ATermAppl x)
	{
		realize(_kb.getABox().getIndividual(x));
	}

	private void realize(final Individual x)
	{
		final Map<ATermAppl, Boolean> marked = new ConcurrentHashMap<>();

		final List<ATermAppl> obviousTypes = new ArrayList<>();
		final List<ATermAppl> obviousNonTypes = new ArrayList<>();

		_kb.getABox().getObviousTypes(x.getName(), obviousTypes, obviousNonTypes);

		for (final ATermAppl c : obviousTypes)
		{
			// since nominals can be returned by getObviousTypes
			// we need the following check
			if (!_taxonomyImpl.contains(c))
				continue;

			mark(_taxonomyImpl.getAllEquivalents(c), marked, true);
			mark(_taxonomyImpl.getFlattenedSupers(c, /* direct = */true), marked, true);

			// FIXME: markToldDisjoints operates on a map key'd with
			// TaxonomyNodes, not ATermAppls
			// markToldDisjoints( c, false );
		}

		for (final ATermAppl c : obviousNonTypes)
		{
			mark(_taxonomyImpl.getAllEquivalents(c), marked, false);
			mark(_taxonomyImpl.getFlattenedSubs(c, /* direct = */true), marked, false);
		}

		realize(x.getName(), ATermUtils.TOP, marked);
	}

	private boolean realize(final ATermAppl n, final ATermAppl c, final Map<ATermAppl, Boolean> marked)
	{
		boolean realized = false;

		if (c.equals(ATermUtils.BOTTOM))
			return false;

		boolean isType;
		if (marked.containsKey(c))
			isType = marked.get(c).booleanValue();
		else
		{
			long time = 0, count = 0;
			if (_logger.isLoggable(Level.FINER))
			{
				time = System.currentTimeMillis();
				count = _kb.getABox().getStats()._consistencyCount;
				_logger.finer("Type checking for [" + format(n) + ", " + format(c) + "]...");
			}

			isType = _kb.isType(n, c);
			marked.put(c, isType);

			if (_logger.isLoggable(Level.FINER))
			{
				final String sign = _kb.getABox().getStats()._consistencyCount > count ? "+" : "-";
				time = System.currentTimeMillis() - time;
				_logger.finer("done (" + (isType ? "+" : "-") + ") (" + sign + time + "ms)");
			}
		}

		if (isType)
		{
			final TaxonomyNode<ATermAppl> node = _taxonomyImpl.getNode(c);

			for (final TaxonomyNode<ATermAppl> sub : node.getSubs())
			{
				final ATermAppl d = sub.getName();
				realized = realize(n, d, marked) || realized;
			}

			// this concept is the most specific concept x belongs to
			// so add it here and return true
			if (!realized)
			{
				@SuppressWarnings("unchecked")
				Set<ATermAppl> instances = (Set<ATermAppl>) node.getDatum(TaxonomyUtils.INSTANCES_KEY);
				if (instances == null)
				{
					instances = new HashSet<>();
					node.putDatum(TaxonomyUtils.INSTANCES_KEY, instances);
				}
				instances.add(n);
				realized = true;
			}
		}

		return realized;
	}

	private boolean realizeByConcepts()
	{
		_monitor.setProgressLength(_classes.size() + 2);
		_monitor.taskStarted();

		clearMarks();

		final Collection<ATermAppl> individuals = _kb.getIndividuals();
		if (!individuals.isEmpty())
			realizeByConcept(ATermUtils.TOP, individuals);
		checkRealizeTimer();

		if (_monitor.isCanceled())
			return false;

		_monitor.taskFinished();

		return true;
	}

	private Set<ATermAppl> realizeByConcept(final ATermAppl c, final Collection<ATermAppl> individuals)
	{
		if (c.equals(ATermUtils.BOTTOM))
			return Collections.emptySet();

		checkRealizeTimer();

		if (_monitor.isCanceled())
			return null;

		final TaxonomyNode<ATermAppl> node = _taxonomyImpl.getNode(c);

		if (node.markIsDefined() && node.getMark())
			return TaxonomyUtils.getAllInstances(_taxonomyImpl, c);

		_monitor.incrementProgress();
		mark(node, true, Propogate.NONE);

		_logger.fine(() -> "Realizing concept " + c);

		final Set<ATermAppl> instances = SetUtils.create(_kb.retrieve(c, individuals));
		final Set<ATermAppl> mostSpecificInstances = SetUtils.create(instances);

		if (!instances.isEmpty())
		{
			for (final TaxonomyNode<ATermAppl> sub : node.getSubs())
			{
				final ATermAppl d = sub.getName();
				final Set<ATermAppl> subInstances = realizeByConcept(d, instances);

				// Returned value can be null if the monitor is canceled
				if (subInstances == null)
					return null;

				mostSpecificInstances.removeAll(subInstances);
			}

			if (!mostSpecificInstances.isEmpty())
				node.putDatum(TaxonomyUtils.INSTANCES_KEY, mostSpecificInstances);
		}

		return instances;
	}

	public void printStats()
	{
		final StringBuilder sb = new StringBuilder(_kb.getABox().getCache().toString());

		@SuppressWarnings("unused")
		int totalExps = 0; // TODO : remove this or print it

		@SuppressWarnings("unused")
		int totalAxioms = 0; // TODO : remove this or print it
		final Iterator<?> i = _taxonomyImpl.depthFirstDatumOnly(ATermUtils.TOP, TaxonomyUtils.SUPER_EXPLANATION_KEY);
		while (i.hasNext())
		{
			@SuppressWarnings("unchecked")
			final Map<ATermAppl, Set<Set<ATermAppl>>> allExps = (Map<ATermAppl, Set<Set<ATermAppl>>>) i.next();
			if (allExps != null)
			{
				totalExps++;
				for (final Set<Set<ATermAppl>> exps : allExps.values())
					for (final Set<ATermAppl> exp : exps)
						totalAxioms += exp.size();
			}
		}
		System.out.println(sb);
	}

	public void printMemory()
	{
		try
		{
			MemUtils.printMemory("Total: ", MemUtils.totalMemory());
			MemUtils.printMemory("Free : ", MemUtils.freeMemory());
			MemUtils.printMemory("Used*: ", MemUtils.totalMemory() - MemUtils.freeMemory());
			MemUtils.runGC();
			MemUtils.printMemory("Used : ", MemUtils.usedMemory());
		}
		catch (final Exception e)
		{
			e.printStackTrace();
		}
	}

	private static String format(final ATermAppl c)
	{
		return ATermUtils.toString(c);
	}

}