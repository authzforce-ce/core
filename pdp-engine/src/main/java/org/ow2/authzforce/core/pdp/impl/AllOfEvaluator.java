/*
 * Copyright 2012-2022 THALES.
 *
 * This file is part of AuthzForce CE.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.ow2.authzforce.core.pdp.impl;

import net.sf.saxon.s9api.XPathCompiler;
import oasis.names.tc.xacml._3_0.core.schema.wd_17.Match;
import org.ow2.authzforce.core.pdp.api.EvaluationContext;
import org.ow2.authzforce.core.pdp.api.IndeterminateEvaluationException;
import org.ow2.authzforce.core.pdp.api.expression.ExpressionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * XACML AllOf evaluator
 *
 * @version $Id: $
 */
public final class AllOfEvaluator
{
    private static final Logger LOGGER = LoggerFactory.getLogger(AllOfEvaluator.class);

    private static final IllegalArgumentException NO_MATCH_EXCEPTION = new IllegalArgumentException(
            "<AllOf> empty. Must contain at least one <Match>");

    /*
     Store the list of Matches as evaluable Match types to avoid casting
     from JAXB MatchType
     during evaluation
     */
    private final transient List<MatchEvaluator> evaluableMatchList;

    /**
     * Instantiates AllOf (evaluator) from XACML-Schema-derived
     * <code>AllOf</code>.
     *
     * @param jaxbMatches   XACML-schema-derived JAXB Match elements
     * @param xPathCompiler XPath compiler corresponding to enclosing policy(set) default
     *                      XPath version
     * @param expFactory    Expression factory
     * @throws java.lang.IllegalArgumentException null {@code expFactory} or null/empty {@code jaxbMatches} or
     *                                            one of the child Match elements in {@code jaxbMatches} is
     *                                            invalid
     */
    public AllOfEvaluator(final List<Match> jaxbMatches, final XPathCompiler xPathCompiler,
                          final ExpressionFactory expFactory) throws IllegalArgumentException
    {
        if (jaxbMatches == null || jaxbMatches.isEmpty())
        {
            throw NO_MATCH_EXCEPTION;
        }

        evaluableMatchList = new ArrayList<>(jaxbMatches.size());
        int matchIndex = 0;
        for (final Match jaxbMatch : jaxbMatches)
        {
            final MatchEvaluator matchEvaluator;
            try
            {
                matchEvaluator = new MatchEvaluator(jaxbMatch, xPathCompiler, expFactory);
            } catch (final IllegalArgumentException e)
            {
                throw new IllegalArgumentException("Invalid <AllOf>'s <Match>#" + matchIndex, e);
            }

            evaluableMatchList.add(matchEvaluator);
            matchIndex++;
        }
    }

    /**
     * Determines whether this <code>AllOf</code> matches the input request
     * (whether it is applicable).Here is the table shown in the specification:
     * <code>
     * <Match> values 						<AllOf> value
     * All True				 			“Match�?
     * No False and at least
     * one "Indeterminate" 				“Indeterminate�?
     * At least one False					"No Match"
     * </code>
     *
     * @param context the representation of the Individual Decision request
     * @param mdpContext
     * 	 * 	 the context of the Multiple Decision request that the {@code context} belongs to if the Multiple Decision Profile is used.
     * @return true iff Match, else No match
     * @throws org.ow2.authzforce.core.pdp.api.IndeterminateEvaluationException Indeterminate
     */
    public boolean match(final EvaluationContext context, final Optional<EvaluationContext> mdpContext) throws IndeterminateEvaluationException
    {
        // atLeastOneIndeterminate = true iff lastIndeterminate != null
        IndeterminateEvaluationException lastIndeterminate = null;

        // index of the current Match in this AllOf
        int childIndex = 0;

        // index of last Indeterminate for enhanced error message
        int lastIndeterminateChildIndex = -1;

        /*
         * By construction, there must be at least one Match
         */
        for (final MatchEvaluator matchEvaluator : evaluableMatchList)
        {
            final boolean isMatched;
            try
            {
                isMatched = matchEvaluator.match(context, mdpContext);
                if (LOGGER.isDebugEnabled())
                {
                    // Beware of autoboxing which causes call to
                    // Boolean.valueOf(...), Integer.valueOf(...)
                    LOGGER.debug("AllOf/Match#{} -> {}", childIndex, isMatched);
                }
            } catch (final IndeterminateEvaluationException e)
            {
                if (LOGGER.isDebugEnabled())
                {
                    // Beware of autoboxing which causes call to
                    // Integer.valueOf(...)
                    LOGGER.debug("AllOf/Match#{} -> Indeterminate", childIndex, e);
                }
                lastIndeterminate = e;
                lastIndeterminateChildIndex = childIndex;
                continue;
            }

            /*
             * At least one False -> No match
             */
            if (!isMatched)
            {
                return false;
            }

            // True (Match) -> continue, all must be true to match
            childIndex += 1;
        }

        // No False (=NO_MATCH) occurred
        // lastIndeterminate == null iff no Indeterminate occurred
        if (lastIndeterminate == null)
        {
            // No False/Indeterminate, i.e. all True -> Match
            return true;
        }

        // No False but at least one Indeterminate (lastIndeterminate != null)
        throw new IndeterminateEvaluationException("Error evaluating <AllOf>'s <Match>#" + lastIndeterminateChildIndex,
                lastIndeterminate);
    }
}
