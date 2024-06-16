// Copyright (c) 2006 - 2008, Clark & Parsia, LLC. <http://www.clarkparsia.com>
// This source code is available under the terms of the Affero General Public
// License v3.
//
// Please see LICENSE.txt for full license terms, including the availability of
// proprietary exceptions.
// Questions, comments, or requests for clarification: licensing@clarkparsia.com

package openllet.test.classification;

import java.util.ArrayList;
import java.util.List;

import org.apache.jena.ontology.Individual;
import org.apache.jena.ontology.OntClass;
import org.apache.jena.ontology.OntModel;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.rdf.model.StmtIterator;
import org.apache.jena.reasoner.ValidityReport;
import org.apache.jena.vocabulary.OWL;
import org.apache.jena.vocabulary.RDFS;
import org.apache.jena.vocabulary.ReasonerVocabulary;

import openllet.jena.PelletReasonerFactory;
import org.junit.Test;

import static org.junit.Assert.*;

public class JenaClassificationTest extends AbstractClassificationTest
{
	@Override
	public void testClassification(final String inputOnt, final String classifiedOnt)
	{
		final OntModel premise = ModelFactory.createOntologyModel(PelletReasonerFactory.THE_SPEC);
		premise.read(inputOnt);
		premise.prepare();

		final Model conclusion = ModelFactory.createDefaultModel();
		conclusion.read(classifiedOnt);

		final StmtIterator stmtIter = conclusion.listStatements();

		final List<String> nonEntailments = new ArrayList<>();
		while (stmtIter.hasNext())
		{
			final Statement stmt = stmtIter.nextStatement();

			boolean entailed = true;
			if (stmt.getPredicate().equals(RDFS.subClassOf))
				entailed = premise.contains(stmt.getSubject(), ReasonerVocabulary.directSubClassOf, stmt.getObject());
			else
				if (stmt.getPredicate().equals(OWL.equivalentClass))
					entailed = premise.contains(stmt);

			if (!entailed)
				if (AbstractClassificationTest.FAIL_AT_FIRST_ERROR)
					fail("Not entailed: " + format(stmt));
				else
				{
					//String x = format(stmt);
					//if (!"[MaterialProperties,subClassOf,CostDriver]".equals(x))
					nonEntailments.add(format(stmt));
					final Individual i = premise.getIndividual(stmt.getSubject().asResource().getURI());
					System.out.println(i);
					System.out.println(stmt.getPredicate());
				}
		}

		assertTrue(nonEntailments.toString(), nonEntailments.isEmpty());
	}

	private static String format(final Statement stmt)
	{
		try
		{
			final StringBuilder sb = new StringBuilder();
			sb.append('[');
			sb.append(stmt.getSubject().getLocalName());
			sb.append(',');
			sb.append(stmt.getPredicate().getLocalName());
			sb.append(',');
			sb.append(stmt.getResource().getLocalName());
			sb.append(']');

			return sb.toString();
		}
		catch (final Exception e)
		{
			e.printStackTrace();

			return stmt.toString();
		}
	}

	@Test
	public void classEqualityTest() throws Exception
	{
		final String fileName = "mine-minimal";
		final String common = "file:" + base + fileName;
		final String inputOnt = common+".owl";
		final OntModel premise = ModelFactory.createOntologyModel(PelletReasonerFactory.THE_SPEC);
		premise.read(inputOnt);

		final String ns = "http://ac.uK/ClassEquality.owl#";
		//R1 and R2 should be the same
		final String R1iri = "&MechanicalEngineeringOntology;R1";

		final OntClass R1 = premise.getOntClass(ns+"R1");
		final OntClass R2 = premise.getOntClass(ns+"R2");
		final OntClass notR2 = premise.getOntClass(ns+"notR2");

		//thus an individual which belongs to R1 but not to R2 cannot exists
		final Individual i = premise.createIndividual(ns+"i", R1);
		i.addOntClass(notR2);
		final ValidityReport r = premise.validate();
		assertFalse(r.isValid());

//		premise.prepare();
//		final StmtIterator stmtIter = premise.listStatements();
//		for(final Statement s : stmtIter.toList()){
//			System.out.println(s);
//		}

	}

}
