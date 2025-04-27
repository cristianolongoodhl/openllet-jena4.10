// Copyright (c) 2006 - 2008, Clark & Parsia, LLC. <http://www.clarkparsia.com>
// This source code is available under the terms of the Affero General Public
// License v3.
//
// Please see LICENSE.txt for full license terms, including the availability of
// proprietary exceptions.
// Questions, comments, or requests for clarification: licensing@clarkparsia.com

package openllet.jena;

import org.apache.jena.ontology.OntModel;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.RDFLanguages;
import org.apache.jena.util.FileManager;

import openllet.core.KBLoader;
import openllet.core.KnowledgeBase;

/**
 * <p>
 * Copyright: Copyright (c) 2007
 * </p>
 * <p>
 * Company: Clark & Parsia, LLC. <http://www.clarkparsia.com>
 * </p>
 *
 * @author Evren Sirin
 */
public class JenaLoader extends KBLoader
{

	private OntModel _model;

	private PelletInfGraph _pellet;

	public JenaLoader()
	{
		clear();
	}

	public PelletInfGraph getGraph()
	{
		return _pellet;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public KnowledgeBase getKB()
	{
		return _pellet.getKB();
	}

	public OntModel getModel()
	{
		return _model;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void load()
	{
		_pellet.prepare(false);
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	protected void parseFile(final String file)
	{
		if (_inputFormat!=null)
			RDFDataMgr.read(_model, file, RDFLanguages.nameToLang(_inputFormat));
		else
			RDFDataMgr.read(_model, file);
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void clear()
	{
		if (_model != null)
			_model.close();
		_model = ModelFactory.createOntologyModel(PelletReasonerFactory.THE_SPEC);
		_pellet = (PelletInfGraph) _model.getGraph();
	}

	/**
	 * Used for the Jena loader
	 *
	 * @param inputFormat
	 */
	public void setInputFormat(final String inputFormat)
	{
		_inputFormat = inputFormat.toUpperCase();
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void setIgnoreImports(final boolean ignoreImports)
	{
		_model.getDocumentManager().setProcessImports(!ignoreImports);
	}

}
