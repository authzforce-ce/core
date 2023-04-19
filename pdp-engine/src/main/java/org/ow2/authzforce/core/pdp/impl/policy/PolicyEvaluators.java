/*
 * Copyright 2012-2023 THALES.
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
package org.ow2.authzforce.core.pdp.impl.policy;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import net.sf.saxon.s9api.*;
import oasis.names.tc.xacml._3_0.core.schema.wd_17.*;
import org.ow2.authzforce.core.pdp.api.*;
import org.ow2.authzforce.core.pdp.api.combining.CombiningAlg;
import org.ow2.authzforce.core.pdp.api.combining.CombiningAlgParameter;
import org.ow2.authzforce.core.pdp.api.combining.CombiningAlgRegistry;
import org.ow2.authzforce.core.pdp.api.combining.ParameterAssignment;
import org.ow2.authzforce.core.pdp.api.expression.BaseXPathCompilerProxy;
import org.ow2.authzforce.core.pdp.api.expression.ExpressionFactory;
import org.ow2.authzforce.core.pdp.api.expression.VariableReference;
import org.ow2.authzforce.core.pdp.api.expression.XPathCompilerProxy;
import org.ow2.authzforce.core.pdp.api.policy.*;
import org.ow2.authzforce.core.pdp.api.value.Value;
import org.ow2.authzforce.core.pdp.impl.BooleanEvaluator;
import org.ow2.authzforce.core.pdp.impl.PepActionExpression;
import org.ow2.authzforce.core.pdp.impl.TargetEvaluators;
import org.ow2.authzforce.core.pdp.impl.rule.RuleEvaluator;
import org.ow2.authzforce.core.pdp.impl.rule.RuleEvaluators;
import org.ow2.authzforce.xacml.identifiers.XPathVersion;
import org.ow2.authzforce.xacml.identifiers.XacmlNodeName;
import org.ow2.authzforce.xacml.identifiers.XacmlStatusCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.bind.JAXBElement;
import java.io.Serializable;
import java.util.*;

/**
 * This class consists exclusively of static methods that operate on or return {@link PolicyEvaluator}s
 *
 * @version $Id: $
 */
public final class PolicyEvaluators
{

    private static final IllegalArgumentException NULL_XACML_COMBINING_ALG_ARG_EXCEPTION = new IllegalArgumentException("Undefined policy/rule combining algorithm registry");
    private static final IllegalArgumentException NULL_EXPRESSION_FACTORY_EXCEPTION = new IllegalArgumentException("Undefined XACML Expression factory/parser");
    private static final IllegalArgumentException NULL_XACML_POLICY_ARG_EXCEPTION = new IllegalArgumentException("Undefined XACML <Policy>");
    private static final IllegalArgumentException NULL_XACML_POLICYSET_ARG_EXCEPTION = new IllegalArgumentException("Undefined XACML <PolicySet>");

    private static final Logger LOGGER = LoggerFactory.getLogger(PolicyEvaluators.class);

    private static final class ImmutableXPathCompiler extends BaseXPathCompilerProxy
    {

        private final ImmutableList<VariableReference<?>> allowedXPathVariables;

        private ImmutableXPathCompiler(final XPathVersion xpathVersion, final Map<String, String> namespacePrefixToUriMap, final List<VariableReference<?>> allowedXPathVariables) throws IllegalArgumentException
        {
            super(xpathVersion, namespacePrefixToUriMap);
            assert allowedXPathVariables != null;
            this.allowedXPathVariables = ImmutableList.copyOf(allowedXPathVariables);
        }

        @Override
        public List<VariableReference<?>> getAllowedVariables()
        {
            return allowedXPathVariables;
        }

        @Override
        public XPathExecutable compile(String source) throws SaxonApiException
        {
            /*
            First compile with setAllowUndeclaredVariables (see above constructor) to discover XPath variables in the input XPath expression.
             */
            /*
             * Why not reuse the same XPathCompiler over and over (make it a class member)? Because it is not immutable, calling XPathCompiler#compile(String) may change the internal state each time, e.g. if there are XPath variables in multiple sources, it is like calling XPathCompiler#declareVariables(...) without reinitializing, i.e. variables add up.
             */
            final XPathCompiler compiler = XmlUtils.newXPathCompiler(xPathVersion, nsPrefixToUriMap);
            compiler.setAllowUndeclaredVariables(true);
            final XPathExecutable xpathExec = compiler.compile(source);
            final Iterator<QName> xpathVarsIterator = xpathExec.iterateExternalVariables();
            if(xpathVarsIterator.hasNext()) {
                if(this.allowedXPathVariables.isEmpty()) {
                    throw new SaxonApiException("Input XPath expression contains variable(s) but there is no (XACML) VariableDefinition in this context: '" +source+"'");
                }
                /*
                XPath variable(s) found, we need to validate them against allowedXPathVariables (from XACML VariableDefinitions in enclosing policy)
                 */
                final XPathCompiler xpathCompiler = XmlUtils.newXPathCompiler(getXPathVersion(), getDeclaredNamespacePrefixToUriMap());
                xpathCompiler.setAllowUndeclaredVariables(false);
                do {
                    final QName xpathVarName = xpathVarsIterator.next();
                    final Optional<VariableReference<?>> matchedVarRef = this.allowedXPathVariables.stream().filter(varRef -> varRef.getXPathVariableName().equals(xpathVarName)).findAny();
                    if(matchedVarRef.isEmpty()) {
                        throw new SaxonApiException("Input XPath expression contains variable '"+xpathVarName+"' but there is no matching (XACML) VariableDefinition in this context");
                    }

                    xpathCompiler.declareVariable(xpathVarName, matchedVarRef.get().getReturnType().getXPathItemType(), OccurrenceIndicator.ONE_OR_MORE);
                } while(xpathVarsIterator.hasNext());
                return xpathCompiler.compile(source);
            }

            // no XPath variable, we can use xpathExec directly.
            return xpathExec;
        }

    }

    /**
     * Factory for returning Deny/Permit policy decision based on combining algorithm evaluation result, evaluation context, initial PEP actions (filled from results of evaluation of child elements by
     * combining algorithm) and applicable Policy identifiers
     */
    private interface DPResultFactory
    {

        DecisionResult getInstance(ExtendedDecision combiningAlgResult, EvaluationContext individualDecisionEvaluationContext, Optional<EvaluationContext> mdpContext, UpdatableList<PepAction> basePepActions,
                                   ImmutableList<PrimaryPolicyMetadata> applicablePolicies);

    }

    private static final DPResultFactory DP_WITHOUT_EXTRA_PEP_ACTION_RESULT_FACTORY = (combiningAlgResult, individualDecisionEvaluationContext, mdpContext, basePepActions, applicablePolicies) -> DecisionResults
            .getInstance(combiningAlgResult, basePepActions.copy(), applicablePolicies);

    private static final class PepActionAppendingDPResultFactory implements DPResultFactory
    {
        /*
         * policy's fully qualifying name for logs: Policy(Set)[ID#vXXX]
         */
        private final String policyToString;
        private final List<PepActionExpression> denyActionExpressions;
        private final List<PepActionExpression> permitActionExpressions;

        private PepActionAppendingDPResultFactory(final String policyId, final List<PepActionExpression> denyActionExpressions, final List<PepActionExpression> permitActionExpressions)
        {
            assert policyId != null && denyActionExpressions != null && permitActionExpressions != null;

            this.policyToString = policyId;
            this.denyActionExpressions = denyActionExpressions;
            this.permitActionExpressions = permitActionExpressions;
        }

        @Override
        public DecisionResult getInstance(final ExtendedDecision combiningAlgResult, final EvaluationContext individualDecisionEvaluationContext, Optional<EvaluationContext> mdpContext, final UpdatableList<PepAction> basePepActions,
                                          final ImmutableList<PrimaryPolicyMetadata> applicablePolicies)
        {
            final List<PepActionExpression> matchingActionExpressions;
            final DecisionType combiningAlgDecision = combiningAlgResult.getDecision();
            switch (combiningAlgDecision)
            {
                case DENY:
                    matchingActionExpressions = this.denyActionExpressions;
                    break;
                case PERMIT:
                    matchingActionExpressions = this.permitActionExpressions;
                    break;
                default:
                    throw new IllegalArgumentException("Invalid decision type for policy obligations/advice: " + combiningAlgDecision + ". Expected: Permit/Deny");
            }

            /*
             * If any of the attribute assignment expressions in an obligation or advice expression with a matching FulfillOn or AppliesTo attribute evaluates to "Indeterminate", then the whole rule,
             * policy, or policy set SHALL be "Indeterminate" (see XACML 3.0 core spec, section 7.18).
             */
            for (final PepActionExpression pepActionExpr : matchingActionExpressions)
            {
                final PepAction pepAction;
                try
                {
                    pepAction = pepActionExpr.evaluate(individualDecisionEvaluationContext, mdpContext);
                } catch (final IndeterminateEvaluationException e)
                {
                    /*
                     * Before we lose the exception information, log it at a higher level because it is an evaluation error (but no critical application error, therefore lower level than error)
                     */
                    LOGGER.info("{}/{Obligation|Advice}Expressions -> Indeterminate", policyToString, e);

                    return DecisionResults.newIndeterminate(combiningAlgDecision, e, applicablePolicies);
                }

                basePepActions.add(pepAction);
            }

            return DecisionResults.getInstance(combiningAlgResult, basePepActions.copy(), applicablePolicies);
        }
    }

    /**
     * Represents a set of CombinerParameters to a combining algorithm that may or may not be associated with a policy/rule
     *
     * @param <T> Type of combined element (Policy, Rule...) with which the CombinerParameters are associated
     */
    public static final class BaseCombiningAlgParameter<T extends Decidable> implements CombiningAlgParameter<T>
    {

        // the element to be combined
        private final T element;

        // the parameters used with this element
        private final ImmutableList<ParameterAssignment> parameters;

        /**
         * Constructor that takes both the element to combine and its associated combiner parameters.
         *
         * @param element                combined element; null if
         * @param jaxbCombinerParameters a (possibly empty) non-null <code>List</code> of <code>CombinerParameter<code>s provided for general use
         * @param xPathCompiler          Policy(Set) default XPath compiler, corresponding to the Policy(Set)'s default XPath version specified in {@link DefaultsType} element; undefined if XPath support disabled for the enclosing Policy(Set) or globally (by PDP configuration)
         * @param expFactory             attribute value factory
         * @throws IllegalArgumentException if one of the CombinerParameters is invalid
         */
        private BaseCombiningAlgParameter(final T element, final List<CombinerParameter> jaxbCombinerParameters, final ExpressionFactory expFactory, final Optional<XPathCompilerProxy> xPathCompiler)
                throws IllegalArgumentException
        {
            this.element = element;
            if (jaxbCombinerParameters == null)
            {
                this.parameters = ImmutableList.of();
            } else
            {
                final List<ParameterAssignment> modifiableParamList = new ArrayList<>(jaxbCombinerParameters.size());
                int paramIndex = 0;
                for (final CombinerParameter jaxbCombinerParam : jaxbCombinerParameters)
                {
                    try
                    {
                        final ParameterAssignment combinerParam = new ParameterAssignment(jaxbCombinerParam, expFactory, xPathCompiler);
                        modifiableParamList.add(combinerParam);
                    } catch (final IllegalArgumentException e)
                    {
                        throw new IllegalArgumentException("Error parsing CombinerParameters/CombinerParameter#" + paramIndex, e);
                    }

                    paramIndex++;
                }

                this.parameters = ImmutableList.copyOf(modifiableParamList);
            }
        }

        /**
         * Returns the combined element. If null, it means, this CombinerElement (i.e. all its CombinerParameters) is not associated with a particular rule
         *
         * @return the combined element
         */
        @Override
        public T getCombinedElement()
        {
            return element;
        }

        /**
         * Returns the <code>CombinerParameterEvaluator</code>s associated with this element.
         *
         * @return a <code>List</code> of <code>CombinerParameterEvaluator</code>s
         */
        @Override
        public List<ParameterAssignment> getParameters()
        {
            return parameters;
        }

    }

    private static final class BasePolicyRefsMetadata implements PolicyRefsMetadata
    {
        private final ImmutableSet<PrimaryPolicyMetadata> refPolicies;
        private final ImmutableList<String> longestPolicyRefChain;

        /**
         * This constructor will make all fields immutable, so do you need to make args immutable before passing them to this.
         *
         * @param refPolicies           policies referenced from the policy
         * @param longestPolicyRefChain longest chain of policy references (Policy(Set)IdReferences) originating from the policy
         */
        private BasePolicyRefsMetadata(final Set<PrimaryPolicyMetadata> refPolicies, final List<String> longestPolicyRefChain)
        {
            assert refPolicies != null && longestPolicyRefChain != null;
            this.refPolicies = ImmutableSet.copyOf(refPolicies);
            this.longestPolicyRefChain = ImmutableList.copyOf(longestPolicyRefChain);
        }

        @Override
        public List<String> getLongestPolicyRefChain()
        {
            return longestPolicyRefChain;
        }

        @Override
        public Set<PrimaryPolicyMetadata> getRefPolicies()
        {
            return refPolicies;
        }

    }

    /**
     * Generic Policy(Set) evaluator. Evaluates to a Decision.
     *
     * @param <T> type of combined child elements in evaluated Policy(Set)
     */
    private static abstract class BaseTopLevelPolicyElementEvaluator<T extends Decidable> implements TopLevelPolicyElementEvaluator
    {
        private static final IllegalArgumentException NULL_POLICY_METADATA_EXCEPTION = new IllegalArgumentException("Undefined Policy(Set) metadata (required)");
        private static final IllegalArgumentException NULL_ALG_EXCEPTION = new IllegalArgumentException("Undefined Policy(Set) combining algorithm ID (required)");

        private static final class EvalResults
        {
            private final String policyId;
            private DecisionResult resultWithTarget = null;
            private DecisionResult resultWithoutTarget = null;

            private EvalResults(final String policyId)
            {
                assert policyId != null;
                this.policyId = policyId;
            }

            private void setResult(final boolean skipTarget, final DecisionResult result)
            {
                assert result != null;
                if (skipTarget)
                {
                    if (resultWithoutTarget != null)
                    {
                        throw new UnsupportedOperationException("Policy(Set) '" + policyId + "+': evaluation result (skipTarget = true) already set in this context");
                    }

                    resultWithoutTarget = result;
                } else
                {
                    if (resultWithoutTarget != null)
                    {
                        throw new UnsupportedOperationException("Policy(Set) '" + policyId + "' : evaluation result (skipTarget = false) already set in this context");
                    }

                    resultWithTarget = result;
                }
            }
        }

        // non-null
        private final PrimaryPolicyMetadata policyMetadata;

        // non-null
        private final BooleanEvaluator targetEvaluator;

        // non-null
        private final CombiningAlg.Evaluator combiningAlgEvaluator;

        // non-null
        private final DPResultFactory decisionResultFactory;

        // non-null
        private final List<VariableReference<?>> localVariableAssignmentExpressions;

        private transient final Set<PrimaryPolicyMetadata> enclosedPolicies;

        private transient final String requestScopedEvalResultsCacheKey;

        /**
         * Instantiates an evaluator
         *
         * @param combinedElementClass combined element class
         * @param policyMetadata       policy metadata (type, ID, version...)
         * @param policyTargetEvaluator         Policy(Set) Target evaluator
         * @param combinedElements     child elements combined in the policy(set) by {@code combiningAlg}, in order of declaration
         * @param combinerParameters   combining algorithm parameters, in order of declaration
         * @param combiningAlgId       (policy/rule-)combining algorithm ID
         * @param obligationExps       ObligationExpressions
         * @param adviceExps           AdviceExpressions
         * @param xPathCompiler XPath compiler to be used in Obligation/Advice expressions, if XPath support enabled for this Policy(Set).
         * @param expressionFactory    Expression factory/parser
         * @param combiningAlgRegistry rule/policy combining algorithm registry
         * @throws IllegalArgumentException if {@code policyMetadata == null || combiningAlgId  == null}
         */
        protected BaseTopLevelPolicyElementEvaluator(final Class<T> combinedElementClass, final PrimaryPolicyMetadata policyMetadata, final BooleanEvaluator policyTargetEvaluator, final ImmutableList<VariableReference<?>> localVariables, final String combiningAlgId,
                                                     final ImmutableList<T> combinedElements, final ImmutableList<CombiningAlgParameter<? extends T>> combinerParameters, final List<ObligationExpression> obligationExps,
                                                     final List<AdviceExpression> adviceExps, final ExpressionFactory expressionFactory,
                                                     final CombiningAlgRegistry combiningAlgRegistry, final Optional<XPathCompilerProxy> xPathCompiler) throws IllegalArgumentException
        {
            if (policyMetadata == null)
            {
                throw NULL_POLICY_METADATA_EXCEPTION;
            }

            if (combiningAlgId == null)
            {
                throw NULL_ALG_EXCEPTION;
            }

            this.policyMetadata = policyMetadata;

            this.targetEvaluator = policyTargetEvaluator;

            final CombiningAlg<T> combiningAlg;
            try
            {
                combiningAlg = combiningAlgRegistry.getAlgorithm(combiningAlgId, combinedElementClass);
            } catch (final IllegalArgumentException e)
            {
                throw new IllegalArgumentException(
                        this + ": Unknown/unsupported " + (RuleEvaluator.class.isAssignableFrom(combinedElementClass) ? "rule" : "policy") + "-combining algorithm ID = '" + combiningAlgId + "'", e);
            }

            this.combiningAlgEvaluator = combiningAlg.getInstance(combinerParameters, combinedElements);

            this.localVariableAssignmentExpressions = localVariables;

            if ((obligationExps == null || obligationExps.isEmpty()) && (adviceExps == null || adviceExps.isEmpty()))
            {
                // no PEP obligation/advice
                this.decisionResultFactory = DP_WITHOUT_EXTRA_PEP_ACTION_RESULT_FACTORY;
            } else
            {
                final int maxNumOfPepActionExpressions = (obligationExps == null ? 0 : obligationExps.size()) + (adviceExps == null ? 0 : adviceExps.size());
                final List<PepActionExpression> denyPepActionExpressions = new ArrayList<>(maxNumOfPepActionExpressions);
                final List<PepActionExpression> permitPepActionExpressions = new ArrayList<>(maxNumOfPepActionExpressions);
                if (obligationExps != null)
                {
                    obligationExps.forEach(obligationExp ->
                    {
                        final List<PepActionExpression> pepActionExpressions = obligationExp.getFulfillOn() == EffectType.DENY ? denyPepActionExpressions : permitPepActionExpressions;
                        pepActionExpressions
                                .add(new PepActionExpression(obligationExp.getObligationId(), true, obligationExp.getAttributeAssignmentExpressions(), expressionFactory, xPathCompiler));
                    });
                }

                if (adviceExps != null)
                {
                    adviceExps.forEach(adviceExp ->
                    {
                        final List<PepActionExpression> pepActionExpressions = adviceExp.getAppliesTo() == EffectType.DENY ? denyPepActionExpressions : permitPepActionExpressions;
                        pepActionExpressions.add(new PepActionExpression(adviceExp.getAdviceId(), false, adviceExp.getAttributeAssignmentExpressions(), expressionFactory, xPathCompiler));
                    });
                }

                this.decisionResultFactory = new PepActionAppendingDPResultFactory(this.policyMetadata.toString(), denyPepActionExpressions, permitPepActionExpressions);
            }

            final Set<PrimaryPolicyMetadata> mutableEnclosedPolicies = HashCollections.newUpdatableSet();
            mutableEnclosedPolicies.add(policyMetadata);
            combinedElements.stream().filter(e -> e instanceof PolicyEvaluator).forEach(e ->
            {
                final Set<PrimaryPolicyMetadata> policiesEnclosedInChildPolicy = ((PolicyEvaluator) e).getEnclosedPolicies();
                if (!Collections.disjoint(mutableEnclosedPolicies, policiesEnclosedInChildPolicy))
                {
                    throw new IllegalArgumentException(this.policyMetadata + ": duplicate policy (ID,version)! (One of these is enclosed multiple times: " + policiesEnclosedInChildPolicy + ")");
                }

                mutableEnclosedPolicies.addAll(policiesEnclosedInChildPolicy);
            });

            /*
             * Add itself
             */
            this.enclosedPolicies = ImmutableSet.copyOf(mutableEnclosedPolicies);

            /*
             * Define keys for caching the result of #evaluate() in the request context (see Object#toString())
             */
            this.requestScopedEvalResultsCacheKey = this.getClass().getName() + '@' + Integer.toHexString(hashCode());
        }

        private IndeterminateEvaluationException enforceNoNullCauseForIndeterminate(final Optional<IndeterminateEvaluationException> causeForIndeterminate)
        {
            if (causeForIndeterminate.isPresent())
            {
                return causeForIndeterminate.get();
            }

            // not present
            LOGGER.error("{} evaluation failed for UNKNOWN reason. Make sure all AuthzForce extensions provide meaningful information when throwing instances of {}", this,
                    IndeterminateEvaluationException.class);
            return new IndeterminateEvaluationException("Cause unknown/hidden", XacmlStatusCode.PROCESSING_ERROR.value());
        }

        @Override
        public final Set<PrimaryPolicyMetadata> getEnclosedPolicies()
        {
            return this.enclosedPolicies;
        }

        private void assignVariables(final EvaluationContext individualDecisionContext, final Optional<EvaluationContext> mdpContext) throws IndeterminateEvaluationException
        {
            for (final VariableReference<?> varRef : this.localVariableAssignmentExpressions)
            {
                final Value varVal = varRef.evaluate(individualDecisionContext, mdpContext);
                individualDecisionContext.putVariableIfAbsent(varRef, varVal);
            }
        }

        /**
         * Policy(Set) evaluation with option to skip Target evaluation. The option is to be used by Only-one-applicable algorithm with value 'true', after calling
         * {@link TopLevelPolicyElementEvaluator#isApplicableByTarget(EvaluationContext, Optional)} in particular.
         *
         * @param individualDecisionContext evaluation individualDecisionContext
         * @param skipTarget                whether to evaluate the Target.
         * @return decision result
         */
        @Override
        public final DecisionResult evaluate(final EvaluationContext individualDecisionContext, final Optional<EvaluationContext> mdpContext, final boolean skipTarget)
        {
            /*
             * check whether the result is already cached in the evaluation individualDecisionContext
             */
            final Object cachedValue = individualDecisionContext.getOther(this.requestScopedEvalResultsCacheKey);
            final EvalResults cachedResults;
            if (cachedValue instanceof EvalResults)
            {
                cachedResults = (EvalResults) cachedValue;
            } else
            {
                cachedResults = null;
            }

            DecisionResult newResult = null;
            final UpdatableList<PepAction> updatablePepActions;

            /*
             * We add the current policy (this.refToSelf) to the applicablePolicyIdList only at the end when we know for sure the result is different from NotApplicable
             */
            final UpdatableList<PrimaryPolicyMetadata> updatableApplicablePolicyIdList;

            try
            {
                final ExtendedDecision algResult;
                if (skipTarget)
                {
                    // check cached result
                    if (cachedResults != null && cachedResults.resultWithoutTarget != null)
                    {
                        LOGGER.debug("{} -> {} (result from individualDecisionContext cache with skipTarget=true)", this, cachedResults.resultWithoutTarget);
                        return cachedResults.resultWithoutTarget;
                    }

                    // evaluate with combining algorithm
                    /*
                     * But first compute the variables that maybe used in this scope
                     */
                    /*
                     * Make the value of local variables available in this scope. Note that not only Apply expressions may use variables but also PDP extensions such as Attribute/Policy Providers
                     * possibly.
                     */
                    try
                    {
                        assignVariables(individualDecisionContext, mdpContext);
                    } catch (final IndeterminateEvaluationException e)
                    {
                        LOGGER.error("{} -> Indeterminate (failed to evaluate one of the local Variables defined in this policy))", this);
                        return DecisionResults.newIndeterminate(null, e, ImmutableList.of());
                    }

                    updatablePepActions = UpdatableCollections.newUpdatableList();
                    updatableApplicablePolicyIdList = individualDecisionContext.isApplicablePolicyIdListRequested() ? UpdatableCollections.newUpdatableList()
                            : UpdatableCollections.emptyList();

                    algResult = combiningAlgEvaluator.evaluate(individualDecisionContext, mdpContext, updatablePepActions, updatableApplicablePolicyIdList);
                    LOGGER.debug("{}/Algorithm -> {}", this, algResult);
                } else
                {
                    if (cachedResults != null && cachedResults.resultWithTarget != null)
                    {
                        LOGGER.debug("{} -> {} (result from individualDecisionContext cache with skipTarget=false)", this, cachedResults.resultWithTarget);
                        return cachedResults.resultWithTarget;
                    }

                    // evaluate target
                    IndeterminateEvaluationException targetMatchIndeterminateException = null;
                    try
                    {
                        if (!isApplicableByTarget(individualDecisionContext, mdpContext))
                        {
                            LOGGER.debug("{}/Target -> No-match", this);
                            LOGGER.debug("{} -> NotApplicable", this);
                            newResult = DecisionResults.SIMPLE_NOT_APPLICABLE;
                            return newResult;
                        }

                        // Target Match
                        LOGGER.debug("{}/Target -> Match", this);
                    } catch (final IndeterminateEvaluationException e)
                    {
                        targetMatchIndeterminateException = e;
                        /*
                         * Before we lose the exception information, log it at a higher level because it is an evaluation error (but no critical application error, therefore lower level than error)
                         */
                        LOGGER.info("{}/Target -> Indeterminate", this, e);
                    }

                    // evaluate with combining algorithm
                    /*
                     * First make the value of local variables available in this scope. Note that not only Apply expressions may use variables but also PDP extensions such as Attribute/Policy
                     * Providers possibly.
                     */
                    try
                    {
                        assignVariables(individualDecisionContext, mdpContext);
                    } catch (final IndeterminateEvaluationException e)
                    {
                        LOGGER.error("{} -> Indeterminate (failed to evaluate one of the local Variables defined in this policy))", this, e);
                        return DecisionResults.newIndeterminate(null, e, ImmutableList.of());
                    }

                    updatablePepActions = UpdatableCollections.newUpdatableList();
                    updatableApplicablePolicyIdList = individualDecisionContext.isApplicablePolicyIdListRequested() ? UpdatableCollections.newUpdatableList()
                            : UpdatableCollections.emptyList();
                    algResult = combiningAlgEvaluator.evaluate(individualDecisionContext, mdpContext, updatablePepActions, updatableApplicablePolicyIdList);
                    LOGGER.debug("{}/Algorithm -> {}", this, algResult);

                    if (targetMatchIndeterminateException != null)
                    {
                        // Target is Indeterminate
                        /*
                         * Implement Extended Indeterminate according to table 7 of section 7.14 (XACML 3.0 Core). If the combining alg value is Indeterminate, use its extended Indeterminate value as
                         * this evaluation result's extended Indeterminate value; else (Permit or Deny) as our extended indeterminate value (part between {} in XACML notation).
                         */
                        final DecisionType algDecision = algResult.getDecision();

                        switch (algDecision)
                        {
                            case NOT_APPLICABLE:
                                newResult = DecisionResults.getNotApplicable(algResult.getStatus());
                                break;
                            case PERMIT:
                            case DENY:
                                /*
                                 * Result != NotApplicable -> consider current policy as applicable
                                 */
                                updatableApplicablePolicyIdList.add(this.policyMetadata);
                                newResult = DecisionResults.newIndeterminate(algDecision, targetMatchIndeterminateException, updatableApplicablePolicyIdList.copy());
                                break;
                            default: // INDETERMINATE
                                /*
                                 * Result != NotApplicable -> consider current policy as applicable
                                 */
                                updatableApplicablePolicyIdList.add(this.policyMetadata);
                                newResult = DecisionResults.newIndeterminate(algResult.getExtendedIndeterminate(), targetMatchIndeterminateException, updatableApplicablePolicyIdList.copy());
                                break;
                        }

                        /*
                         * newResult must be initialized and used as return variable at this point, in order to be used in finally{} block below
                         */
                        return newResult;
                    }
                    // Else Target Match
                } // End of Target evaluation

                /*
                 * Target Match (or assumed Match if skipTarget=true) -> the policy decision is the one from the combining algorithm
                 */
                /*
                 * The spec is unclear about what is considered an "applicable" policy, therefore in what case should we add the policy to the PolicyIdentifierList in the final XACML Result. See the
                 * discussion here for more info: https://lists.oasis-open.org/archives/xacml-comment/201605/ msg00004.html. Here we choose to consider a policy applicable if and only if its
                 * evaluation does not return NotApplicable.
                 */
                final DecisionType algResultDecision = algResult.getDecision();
                final Optional<ImmutableXacmlStatus> algResultStatus = algResult.getStatus();
                switch (algResultDecision)
                {
                    case NOT_APPLICABLE:
                        /*
                         * Final evaluation result is NotApplicable, so we don't add to applicable policy identifier list
                         */
                        newResult = DecisionResults.getNotApplicable(algResultStatus);
                        return newResult;

                    case INDETERMINATE:
                        /*
                         * Final result is the Indeterminate from algResult (no PEP actions), XACML §7.12, 7.13
                         *
                         * Result != NotApplicable -> consider current policy as applicable
                         */
                        updatableApplicablePolicyIdList.add(this.policyMetadata);

                        newResult = DecisionResults.newIndeterminate(algResultDecision, enforceNoNullCauseForIndeterminate(algResult.getCauseForIndeterminate()),
                                updatableApplicablePolicyIdList.copy());
                        return newResult;

                    default:
                        // Permit/Deny decision
                        /*
                         * Result != NotApplicable -> consider current policy as applicable
                         */
                        updatableApplicablePolicyIdList.add(this.policyMetadata);
                        newResult = this.decisionResultFactory.getInstance(algResult, individualDecisionContext, mdpContext, updatablePepActions, updatableApplicablePolicyIdList.copy());
                        return newResult;
                }
            } finally
            {
                // remove local variables from individualDecisionContext
                for (final VariableReference<?> varRef : this.localVariableAssignmentExpressions)
                {
                    individualDecisionContext.removeVariable(varRef.getVariableId());
                }

                // update cache with new result
                if (newResult != null)
                {
                    if (cachedResults == null)
                    {
                        final EvalResults newCachedResults = new EvalResults(this.policyMetadata.getId());
                        newCachedResults.setResult(skipTarget, newResult);
                        individualDecisionContext.putOther(this.requestScopedEvalResultsCacheKey, newCachedResults);
                    } else
                    {
                        cachedResults.setResult(skipTarget, newResult);
                    }
                }
            }
        }

        @Override
        public final boolean isApplicableByTarget(final EvaluationContext context, Optional<EvaluationContext> mdpContext) throws IndeterminateEvaluationException
        {
            return targetEvaluator.evaluate(context, mdpContext);
        }

        @Override
        public final DecisionResult evaluate(final EvaluationContext context, final Optional<EvaluationContext> mdpContext)
        {
            return evaluate(context, mdpContext, false);
        }

        @Override
        public final TopLevelPolicyElementType getPolicyElementType()
        {
            return this.policyMetadata.getType();
        }

        @Override
        public final String getPolicyId()
        {
            return this.policyMetadata.getId();
        }

        @Override
        public final PolicyVersion getPolicyVersion()
        {
            return this.policyMetadata.getVersion();
        }

        @Override
        public final PrimaryPolicyMetadata getPrimaryPolicyMetadata()
        {
            return this.policyMetadata;
        }

        @Override
        public final String toString()
        {
            return this.policyMetadata.toString();
        }

        @Override
        public final int hashCode()
        {
            return this.policyMetadata.hashCode();
        }

        @Override
        public final boolean equals(final Object obj)
        {
            // Effective Java - Item 8
            if (this == obj)
            {
                return true;
            }

            if (!(obj instanceof TopLevelPolicyElementEvaluator))
            {
                return false;
            }

            final TopLevelPolicyElementEvaluator other = (TopLevelPolicyElementEvaluator) obj;
            /*
             * We ignore the policyIssuer because it is no part of PolicyReferences, therefore we consider it is not part of the Policy uniqueness
             */
            return this.policyMetadata.equals(other.getPrimaryPolicyMetadata());
        }

    }

    private static final class StaticBaseTopLevelPolicyElementEvaluator<T extends Decidable> extends BaseTopLevelPolicyElementEvaluator<T> implements StaticTopLevelPolicyElementEvaluator
    {
        private transient final Optional<PolicyRefsMetadata> extraPolicyMetadata;

        private StaticBaseTopLevelPolicyElementEvaluator(final Class<T> combinedElementClass, final PrimaryPolicyMetadata policyMetadata, final Optional<PolicyRefsMetadata> extraPolicyMetadata,
                                                         final BooleanEvaluator policyTargetEvaluator, final ImmutableList<VariableReference<?>> localVariables, final String combiningAlgId, final ImmutableList<T> combinedElements, final ImmutableList<CombiningAlgParameter<? extends T>> combinerParameters,
                                                         final List<ObligationExpression> obligationExps, final List<AdviceExpression> adviceExps,
                                                         final ExpressionFactory expressionFactory, final CombiningAlgRegistry combiningAlgRegistry ,final Optional<XPathCompilerProxy> xPathCompiler) throws IllegalArgumentException
        {
            super(combinedElementClass, policyMetadata, policyTargetEvaluator, localVariables, combiningAlgId, combinedElements, combinerParameters, obligationExps, adviceExps,
                    expressionFactory, combiningAlgRegistry, xPathCompiler);
            this.extraPolicyMetadata = extraPolicyMetadata;
        }

        @Override
        public Optional<PolicyRefsMetadata> getPolicyRefsMetadata()
        {
            return this.extraPolicyMetadata;
        }

    }

    /**
     * This class is responsible for evaluating XACML Policy(Set)IdReferences.
     */
    private static abstract class PolicyRefEvaluator implements PolicyEvaluator
    {
        protected final TopLevelPolicyElementType referredPolicyType;
        protected final String refPolicyId;
        // and version constraints on this reference
        protected final Optional<PolicyVersionPatterns> versionConstraints;

        private transient final String toString;
        private transient final int hashCode;

        /**
         * Get Policy reference description
         *
         * @param refPolicyType      type of referenced policy (PolicySet for PolicySetIdReference or Policy for PolicyIdReference)
         * @param policyRefId        referenced policy ID
         * @param versionConstraints referenced policy version constraints
         * @return description
         */
        private static String toString(final TopLevelPolicyElementType refPolicyType, final String policyRefId, final Optional<PolicyVersionPatterns> versionConstraints)
        {
            return refPolicyType + "IdReference[Id=" + policyRefId + ", " + versionConstraints + "]";
        }

        private PolicyRefEvaluator(final TopLevelPolicyElementType refPolicyType, final String policyId, final Optional<PolicyVersionPatterns> versionConstraints)
        {
            assert policyId != null && refPolicyType != null;
            this.refPolicyId = policyId;
            this.versionConstraints = versionConstraints;
            this.referredPolicyType = refPolicyType;
            this.toString = toString(referredPolicyType, policyId, versionConstraints);
            this.hashCode = Objects.hash(this.referredPolicyType, this.refPolicyId, this.versionConstraints);
        }

        @Override
        public final Set<PrimaryPolicyMetadata> getEnclosedPolicies()
        {
            return Collections.emptySet();
        }

        @Override
        public final DecisionResult evaluate(final EvaluationContext context, final Optional<EvaluationContext> mdpContext)
        {
            return evaluate(context, mdpContext, false);
        }

        /*
         * (non-Javadoc)
         *
         * @see org.ow2.authzforce.core.pdp.api.policy.PolicyEvaluator#getPolicyElementType()
         */
        @Override
        public final TopLevelPolicyElementType getPolicyElementType()
        {
            return this.referredPolicyType;
        }

        /*
         * (non-Javadoc)
         *
         * @see org.ow2.authzforce.core.pdp.api.policy.PolicyEvaluator#getPolicyId()
         */
        @Override
        public final String getPolicyId()
        {
            return this.refPolicyId;
        }

        /*
         * (non-Javadoc)
         *
         * @see java.lang.Object#toString()
         */
        @Override
        public final String toString()
        {
            return toString;
        }

        @Override
        public final int hashCode()
        {
            return hashCode;
        }

        @Override
        public final boolean equals(final Object obj)
        {
            // Effective Java - Item 8
            if (this == obj)
            {
                return true;
            }

            // if not both PolicyEvaluators or not both PolicySetEvaluators
            if (!(obj instanceof PolicyRefEvaluator))
            {
                return false;
            }

            final PolicyRefEvaluator other = (PolicyRefEvaluator) obj;
            /*
             * We ignore the policyIssuer because it is no part of PolicyReferences, therefore we consider it is not part of the Policy uniqueness
             */
            return this.referredPolicyType.equals(other.referredPolicyType) && this.refPolicyId.equals(other.refPolicyId) && Objects.equals(this.versionConstraints, other.versionConstraints);
        }

    }

    /**
     * @param referredPolicy policy that this Policy reference refers to
     * @return extra policy metadata
     * @throws IndeterminateEvaluationException if the extra policy metadata of {@code referredPolicy} could not be determined in {@code evalCtx} (with
     *                                          {@link TopLevelPolicyElementEvaluator#getPolicyRefsMetadata(EvaluationContext, Optional)} )
     */
    private static PolicyRefsMetadata newPolicyRefExtraMetadata(final TopLevelPolicyElementEvaluator referredPolicy, final EvaluationContext evalCtx, final Optional<EvaluationContext> mdpContext) throws IndeterminateEvaluationException
    {
        assert referredPolicy != null;

        final PrimaryPolicyMetadata referredPolicyMetadata = referredPolicy.getPrimaryPolicyMetadata();
        final Optional<PolicyRefsMetadata> referredPolicyRefsMetadata = referredPolicy.getPolicyRefsMetadata(evalCtx, mdpContext);
        final Set<PrimaryPolicyMetadata> newRefPolicies;
        final List<String> newLongestPolicyRefChain;
        if (referredPolicyRefsMetadata.isPresent())
        {
            final Set<PrimaryPolicyMetadata> childRefPolicies = referredPolicyRefsMetadata.get().getRefPolicies();
            // LinkedHashSet to preserve order
            newRefPolicies = new LinkedHashSet<>(childRefPolicies.size() + 1);
            newRefPolicies.addAll(childRefPolicies);
            newRefPolicies.add(referredPolicyMetadata);

            final List<String> referredPolicyLongestRefChain = referredPolicyRefsMetadata.get().getLongestPolicyRefChain();
            newLongestPolicyRefChain = new ArrayList<>(referredPolicyLongestRefChain.size() + 1);
            newLongestPolicyRefChain.add(referredPolicy.getPolicyId());
            newLongestPolicyRefChain.addAll(referredPolicyLongestRefChain);
        } else
        {
            newRefPolicies = Sets.newHashSet(referredPolicyMetadata);
            newLongestPolicyRefChain = Collections.singletonList(referredPolicy.getPolicyId());
        }

        return new BasePolicyRefsMetadata(newRefPolicies, newLongestPolicyRefChain);
    }

    private static final class StaticPolicySetChildRefsMetadataProvider
    {
        // LinkedHashSet to preserve order
        private final Set<PrimaryPolicyMetadata> refPolicies = new LinkedHashSet<>();
        private final List<String> longestPolicyRefChain = new ArrayList<>();

        private StaticPolicySetChildRefsMetadataProvider(final PrimaryPolicyMetadata primaryPolicyMetadata)
        {
            assert primaryPolicyMetadata != null;
        }

        private Optional<PolicyRefsMetadata> getMetadata()
        {
            return refPolicies.isEmpty() ? Optional.empty() : Optional.of(new BasePolicyRefsMetadata(refPolicies, longestPolicyRefChain));
        }

        // PMD does not see that this method is used in lambda expressions
        @SuppressWarnings("PMD.UnusedPrivateMethod")
        private void updateMetadata(final PolicyRefsMetadata childPolicyRefsMetadata)
        {
            assert childPolicyRefsMetadata != null;

            // Modify refPolicies
            refPolicies.addAll(childPolicyRefsMetadata.getRefPolicies());

            /*
             * update the longest policy ref chain depending on the length of the longest in this child policy element
             */
            final List<String> childLongestPolicyRefChain = childPolicyRefsMetadata.getLongestPolicyRefChain();
            if (childLongestPolicyRefChain.size() > longestPolicyRefChain.size())
            {
                longestPolicyRefChain.clear();
                longestPolicyRefChain.addAll(childLongestPolicyRefChain);
            }
        }
    }

    private static final class DynamicPolicySetChildRefsMetadataProvider
    {
        private static final class GetMetadataResult
        {
            private final Optional<PolicyRefsMetadata> extraMetadata;

            private GetMetadataResult(final Optional<PolicyRefsMetadata> metadata)
            {
                this.extraMetadata = metadata;
            }

        }

        private final List<PolicyEvaluator> childPolicySetElementsOrRefs = new ArrayList<>();

        private transient final String requestScopedCacheKey;

        private DynamicPolicySetChildRefsMetadataProvider()
        {
            /*
             * Define a key for caching the result of #getMetadata() in the request context (see Object#toString())
             */
            this.requestScopedCacheKey = this.getClass().getName() + '@' + Integer.toHexString(hashCode());
        }

        private void addChildPolicySetElementOrRef(final PolicyEvaluator childElement)
        {
            childPolicySetElementsOrRefs.add(childElement);
        }

        private Optional<PolicyRefsMetadata> getMetadata(final EvaluationContext evalCtx, final Optional<EvaluationContext> mdpCtx) throws IndeterminateEvaluationException
        {
            /*
             * check whether the result is already cached in the evaluation context
             */
            final Object cachedValue = evalCtx.getOther(requestScopedCacheKey);
            if (cachedValue instanceof GetMetadataResult)
            {
                final GetMetadataResult result = (GetMetadataResult) cachedValue;
                return result.extraMetadata;
            }

            /*
             * cachedValue == null, i.e. result not cached yet; or cachedValue of the wrong type (unexpected), so we just overwrite with proper type
             */
            final Set<PrimaryPolicyMetadata> refPolicies = new LinkedHashSet<>();
            final List<String> longestPolicyRefChain = new ArrayList<>();
            for (final PolicyEvaluator policyRef : childPolicySetElementsOrRefs)
            {
                final Optional<PolicyRefsMetadata> extraMetadata = policyRef.getPolicyRefsMetadata(evalCtx, mdpCtx);
                if (extraMetadata.isPresent())
                {
                    refPolicies.addAll(extraMetadata.get().getRefPolicies());
                    final List<String> policyRefLongestPolicyRefChain = extraMetadata.get().getLongestPolicyRefChain();
                    if (policyRefLongestPolicyRefChain.size() > longestPolicyRefChain.size())
                    {
                        longestPolicyRefChain.clear();
                        longestPolicyRefChain.addAll(policyRefLongestPolicyRefChain);
                    }
                }
            }

            final Optional<PolicyRefsMetadata> extraMetadata = refPolicies.isEmpty() ? Optional.empty() : Optional.of(new BasePolicyRefsMetadata(refPolicies, longestPolicyRefChain));
            final GetMetadataResult newCachedValue = new GetMetadataResult(extraMetadata);
            evalCtx.putOther(requestScopedCacheKey, newCachedValue);
            return extraMetadata;
        }
    }

    private static final class DynamicPolicySetEvaluator extends BaseTopLevelPolicyElementEvaluator<PolicyEvaluator>
    {
        private transient final DynamicPolicySetChildRefsMetadataProvider extraPolicyMetadataProvider;

        private DynamicPolicySetEvaluator(final PrimaryPolicyMetadata policyMetadata, final DynamicPolicySetChildRefsMetadataProvider extraPolicyMetadataProvider, final BooleanEvaluator policyTargetEvaluator, final ImmutableList<VariableReference<?>> localVariables,
                                          final String combiningAlgId, final ImmutableList<PolicyEvaluator> combinedElements, final ImmutableList<CombiningAlgParameter<? extends PolicyEvaluator>> combinerParameters,
                                          final List<ObligationExpression> obligationExps, final List<AdviceExpression> adviceExps,
                                          final ExpressionFactory expressionFactory, final CombiningAlgRegistry combiningAlgRegistry, final Optional<XPathCompilerProxy> xPathCompiler) throws IllegalArgumentException
        {
            super(PolicyEvaluator.class, policyMetadata, policyTargetEvaluator, localVariables, combiningAlgId, combinedElements, combinerParameters, obligationExps, adviceExps,
                    expressionFactory, combiningAlgRegistry, xPathCompiler);
            this.extraPolicyMetadataProvider = extraPolicyMetadataProvider;
        }

        @Override
        public Optional<PolicyRefsMetadata> getPolicyRefsMetadata(final EvaluationContext evaluationCtx, final Optional<EvaluationContext> mdpCtx) throws IndeterminateEvaluationException
        {
            return this.extraPolicyMetadataProvider.getMetadata(evaluationCtx, mdpCtx);
        }

    }

    private static final class StaticPolicyRefEvaluator extends PolicyRefEvaluator implements StaticPolicyEvaluator
    {
        /*
         * statically defined policy referenced by this policy reference evaluator
         */
        private final StaticTopLevelPolicyElementEvaluator referredPolicy;
        private transient final Optional<PolicyRefsMetadata> extraMetadata;
        private transient final String isApplicableByTargetCallErrMsg = "Error checking whether Policy(Set) referenced by " + this + " is applicable to the request context";

        private static TopLevelPolicyElementType validate(final PolicyEvaluator referredPolicy)
        {
            return referredPolicy.getPolicyElementType();
        }

        private StaticPolicyRefEvaluator(final StaticTopLevelPolicyElementEvaluator referredPolicy, final Optional<PolicyVersionPatterns> refVersionConstraints)
        {
            super(validate(referredPolicy), referredPolicy.getPolicyId(), refVersionConstraints);
            this.referredPolicy = referredPolicy;
            try
            {
                this.extraMetadata = Optional.of(newPolicyRefExtraMetadata(referredPolicy, null, Optional.empty()));
            } catch (final IndeterminateEvaluationException e)
            {
                throw new RuntimeException(this + ": unexpected error: could not get extra metadata of statically defined policy: " + referredPolicy, e);
            }
        }

        @Override
        public DecisionResult evaluate(final EvaluationContext context, final Optional<EvaluationContext> mdpContext, final boolean skipTarget)
        {
            return referredPolicy.evaluate(context, mdpContext, skipTarget);
        }

        @Override
        public boolean isApplicableByTarget(final EvaluationContext context, final Optional<EvaluationContext> mdpContext) throws IndeterminateEvaluationException
        {
            try
            {
                return referredPolicy.isApplicableByTarget(context, mdpContext);
            } catch (final IndeterminateEvaluationException e)
            {
                throw new IndeterminateEvaluationException(isApplicableByTargetCallErrMsg, e);
            }
        }

        @Override
        public PolicyVersion getPolicyVersion()
        {
            return this.referredPolicy.getPolicyVersion();
        }

        @Override
        public Optional<PolicyRefsMetadata> getPolicyRefsMetadata()
        {
            return this.extraMetadata;
        }

    }

    /**
     * Dynamic Policy/PolicySet reference evaluator
     */
    private static abstract class DynamicTopLevelPolicyElementRefEvaluator extends PolicyRefEvaluator
    {

        protected static final class RefResolvedResult
        {

            private final TopLevelPolicyElementEvaluator resolvedPolicy;
            private final Optional<PolicyRefsMetadata> extraMetadata;
            private final IndeterminateEvaluationException exception;

            private RefResolvedResult(final TopLevelPolicyElementEvaluator policy, final EvaluationContext evalCtx, final Optional<EvaluationContext> mdpCtx) throws IndeterminateEvaluationException
            {
                assert policy != null && evalCtx != null;
                this.exception = null;
                this.resolvedPolicy = policy;
                this.extraMetadata = Optional.of(newPolicyRefExtraMetadata(policy, evalCtx, mdpCtx));
            }

            private RefResolvedResult(final IndeterminateEvaluationException exception)
            {
                assert exception != null;
                this.exception = exception;
                this.resolvedPolicy = null;
                this.extraMetadata = Optional.empty();
            }
        }

        /*
         * This policyProvider to use in finding the referenced policy
         */
        private final PolicyProvider<?> refPolicyProvider;

        private final String requestScopedCacheKey;
        private final ImmutableXacmlStatus policyResolutionErrStatus;

        private DynamicTopLevelPolicyElementRefEvaluator(final TopLevelPolicyElementType policyType, final String policyId, final Optional<PolicyVersionPatterns> versionConstraints,
                                                         final PolicyProvider<?> refPolicyProvider)
        {
            super(policyType, policyId, versionConstraints);
            assert refPolicyProvider != null;
            this.refPolicyProvider = refPolicyProvider;
            /*
             * define a key for caching the resolved policy in the request context (see Object#toString())
             */
            this.requestScopedCacheKey = this.getClass().getName() + '@' + Integer.toHexString(hashCode());
            this.policyResolutionErrStatus = new ImmutableXacmlStatus(XacmlStatusCode.PROCESSING_ERROR.value(), Optional.of("Error resolving " + this + " to the policy to evaluate in the request context"));
        }

        protected final void checkJoinedPolicySetRefChain(final Deque<String> chain1, final List<String> chain2) throws IllegalArgumentException
        {
            refPolicyProvider.joinPolicyRefChains(chain1, chain2);
        }

        protected final TopLevelPolicyElementEvaluator resolvePolicy(final Deque<String> policySetRefChainWithResolvedPolicyIfPolicySet, final EvaluationContext evalCtx, final Optional<EvaluationContext> mdpCtx)
                throws IllegalArgumentException, IndeterminateEvaluationException
        {
            return refPolicyProvider.get(this.referredPolicyType, this.refPolicyId, this.versionConstraints, policySetRefChainWithResolvedPolicyIfPolicySet, evalCtx, mdpCtx);
        }

        protected abstract void checkPolicyRefChain(TopLevelPolicyElementEvaluator nonNullRefResultPolicy, final EvaluationContext evalCtx, final Optional<EvaluationContext> mdpCtx)
                throws IllegalArgumentException, IndeterminateEvaluationException;

        protected abstract TopLevelPolicyElementEvaluator resolvePolicyWithRefDepthCheck(final EvaluationContext evalCtx, final Optional<EvaluationContext> mdpCtx) throws IllegalArgumentException, IndeterminateEvaluationException;

        /**
         * Resolves this to the actual Policy
         *
         * @throws IllegalArgumentException         Error parsing the policy referenced by this. The referenced policy may be parsed on the fly, when calling this method.
         * @throws IndeterminateEvaluationException if error determining the policy referenced by this, e.g. if more than one policy is found
         */
        private RefResolvedResult resolve(final EvaluationContext evalCtx, final Optional<EvaluationContext> mdpCtx) throws IndeterminateEvaluationException, IllegalArgumentException
        {
            // check whether the policy was already resolved in the same context
            final Object cachedValue = evalCtx.getOther(requestScopedCacheKey);
            if (cachedValue instanceof RefResolvedResult)
            {
                final RefResolvedResult result = (RefResolvedResult) cachedValue;
                if (result.exception == null)
                {
                    checkPolicyRefChain(result.resolvedPolicy, evalCtx, mdpCtx);
                    return result;
                }

                throw result.exception;
            }

            /*
             * cachedValue == null, i.e. ref resolution result not cached yet; or cachedValue of the wrong type (unexpected), so we just overwrite with proper type
             */
            try
            {
                final TopLevelPolicyElementEvaluator policy = resolvePolicyWithRefDepthCheck(evalCtx, mdpCtx);
                final RefResolvedResult newCacheValue = new RefResolvedResult(policy, evalCtx, mdpCtx);
                evalCtx.putOther(requestScopedCacheKey, newCacheValue);
                return newCacheValue;
            } catch (final IllegalArgumentException e)
            {
                final IndeterminateEvaluationException resolutionException = new IndeterminateEvaluationException(policyResolutionErrStatus, e);
                final RefResolvedResult newCacheValue = new RefResolvedResult(resolutionException);
                evalCtx.putOther(requestScopedCacheKey, newCacheValue);
                throw resolutionException;
            } catch (final IndeterminateEvaluationException e)
            {
                final RefResolvedResult newCacheValue = new RefResolvedResult(e);
                evalCtx.putOther(requestScopedCacheKey, newCacheValue);
                throw e;
            }
        }

        @Override
        public final DecisionResult evaluate(final EvaluationContext context, final Optional<EvaluationContext> mdpContext, final boolean skipTarget)
        {
            // we must have found a policy
            final RefResolvedResult refResolvedResult;
            try
            {
                refResolvedResult = resolve(context, mdpContext);
            } catch (final IndeterminateEvaluationException e)
            {
                LOGGER.info("", e);
                /*
                 * Dynamic policy ref could not be resolved to an actual policy (-> no applicable policy found)
                 */
                return DecisionResults.newIndeterminate(DecisionType.INDETERMINATE, e, null);
            }

            return refResolvedResult.resolvedPolicy.evaluate(context, mdpContext, skipTarget);
        }

        @Override
        public final boolean isApplicableByTarget(final EvaluationContext evalCtx, final Optional<EvaluationContext> mdpCtx) throws IndeterminateEvaluationException
        {
            final RefResolvedResult refResolvedResult = resolve(evalCtx, mdpCtx);
            return refResolvedResult.resolvedPolicy.isApplicableByTarget(evalCtx, mdpCtx);
        }

        /*
         * (non-Javadoc)
         *
         * @see org.ow2.authzforce.core.pdp.api.policy.PolicyEvaluator#getPolicyVersion(org.ow2.authzforce.core.pdp.api.EvaluationContext)
         */
        @Override
        public final PolicyVersion getPolicyVersion(final EvaluationContext evalCtx, final Optional<EvaluationContext> mdpCtx) throws IndeterminateEvaluationException
        {
            final RefResolvedResult refResolvedResult = resolve(evalCtx, mdpCtx);
            return refResolvedResult.resolvedPolicy.getPolicyVersion();
        }

        @Override
        public final Optional<PolicyRefsMetadata> getPolicyRefsMetadata(final EvaluationContext evalCtx, final Optional<EvaluationContext> mdpCtx) throws IndeterminateEvaluationException
        {
            final RefResolvedResult refResolvedResult = resolve(evalCtx, mdpCtx);
            return refResolvedResult.extraMetadata;
        }

    }

    /**
     * Evaluator of PolicyIdReference with context-dependent resolution
     */
    private static final class DynamicPolicyRefEvaluator extends DynamicTopLevelPolicyElementRefEvaluator
    {
        private DynamicPolicyRefEvaluator(final String policyId, final Optional<PolicyVersionPatterns> versionConstraints, final PolicyProvider<?> refPolicyProvider)
        {
            super(TopLevelPolicyElementType.POLICY, policyId, versionConstraints, refPolicyProvider);
        }

        @Override
        protected void checkPolicyRefChain(final TopLevelPolicyElementEvaluator nonNullRefResultPolicy, final EvaluationContext evalCtx, final Optional<EvaluationContext> mdpCtx)
        {
            // nothing to do for XACML Policy (no nested policy ref)
        }

        @Override
        protected TopLevelPolicyElementEvaluator resolvePolicyWithRefDepthCheck(final EvaluationContext evalCtx, final Optional<EvaluationContext> mdpCtx) throws IllegalArgumentException, IndeterminateEvaluationException
        {
            // no policy ref depth check to do for XACML Policy (no nested policy ref)
            return resolvePolicy(null, evalCtx, mdpCtx);
        }
    }

    private static final class DynamicPolicySetRefEvaluator extends DynamicTopLevelPolicyElementRefEvaluator
    {
        /*
         * Chain of PolicySet Reference leading from root PolicySet down to this reference (included) (Do not use a Queue as it is FIFO, and we need LIFO and iteration in order of insertion, so
         * different from Collections.asLifoQueue(Deque) as well.)
         */
        private final Deque<String> policySetRefChainToThisRefTarget;

        private DynamicPolicySetRefEvaluator(final String policyId, final Optional<PolicyVersionPatterns> versionConstraints, final PolicyProvider<?> refPolicyProvider,
                                             final Deque<String> policySetRefChainWithPolicyIdArgIfPolicySet) throws IllegalArgumentException
        {
            super(TopLevelPolicyElementType.POLICY_SET, policyId, versionConstraints, refPolicyProvider);
            assert policySetRefChainWithPolicyIdArgIfPolicySet != null && !policySetRefChainWithPolicyIdArgIfPolicySet.isEmpty();
            this.policySetRefChainToThisRefTarget = policySetRefChainWithPolicyIdArgIfPolicySet;
        }

        @Override
        protected void checkPolicyRefChain(final TopLevelPolicyElementEvaluator nonNullRefResultPolicy, final EvaluationContext evalCtx, Optional<EvaluationContext> mdpCtx)
                throws IllegalArgumentException, IndeterminateEvaluationException
        {
            assert nonNullRefResultPolicy != null;
            /*
             * Check PolicySet reference depth resulting from resolving this new PolicySet ref
             */
            final Optional<PolicyRefsMetadata> optionalRefsMetadata = nonNullRefResultPolicy.getPolicyRefsMetadata(evalCtx, mdpCtx);
            optionalRefsMetadata.ifPresent(policyRefsMetadata -> checkJoinedPolicySetRefChain(policySetRefChainToThisRefTarget, policyRefsMetadata.getLongestPolicyRefChain()));
        }

        @Override
        protected TopLevelPolicyElementEvaluator resolvePolicyWithRefDepthCheck(final EvaluationContext evalCtx, final Optional<EvaluationContext> mdpCtx) throws IllegalArgumentException, IndeterminateEvaluationException
        {
            return resolvePolicy(policySetRefChainToThisRefTarget, evalCtx, mdpCtx);
        }

    }

    /**
     * Creates Policy handler from XACML Policy element
     *
     * @param policyElement              Policy (XACML)
     * @param parentDefaultXPathCompiler XPath compiler corresponding to parent PolicyDefaults/XPathVersion; undefined if this Policy has no parent Policy (root), or none defined in parent, or XPath disabled by PDP configuration
     * @param namespacePrefixToUriMap    namespace prefix-URI mappings from the original XACML Policy (XML) document, to be used for namespace-aware XPath evaluation; empty iff XPath support disabled or: {@code parentDefaultXPathCompiler.isPresent()} and they can be retrieved already from {@code parentDefaultXPathCompiler.get().getDeclaredNamespacePrefixToUriMap()}
     * @param expressionFactory          Expression factory/parser
     * @param combiningAlgRegistry       rule/policy combining algorithm registry
     * @return instance
     * @throws IllegalArgumentException if any argument is invalid
     */
    public static StaticTopLevelPolicyElementEvaluator getInstance(final Policy policyElement,
                                                                   final ExpressionFactory expressionFactory, final CombiningAlgRegistry combiningAlgRegistry, final Optional<XPathCompilerProxy> parentDefaultXPathCompiler, final Map<String, String> namespacePrefixToUriMap) throws IllegalArgumentException
    {
        if (policyElement == null)
        {
            throw NULL_XACML_POLICY_ARG_EXCEPTION;
        }

        if (expressionFactory == null)
        {
            throw NULL_EXPRESSION_FACTORY_EXCEPTION;
        }

        if (combiningAlgRegistry == null)
        {
            throw NULL_XACML_COMBINING_ALG_ARG_EXCEPTION;
        }

        final String policyId = policyElement.getPolicyId();
        final PolicyVersion policyVersion = new PolicyVersion(policyElement.getVersion());
        final PrimaryPolicyMetadata policyMetadata = new BasePrimaryPolicyMetadata(TopLevelPolicyElementType.POLICY, policyId, policyVersion);
        final BooleanEvaluator targetEvaluator = TargetEvaluators.getInstance(policyElement.getTarget(), expressionFactory, parentDefaultXPathCompiler);

        /*
         * Elements defined in xs:choice of XACML schema type PolicyType: Rules/(Rule)CombinerParameters/VariableDefinitions
         */
        final List<Serializable> policyChoiceElements = policyElement.getCombinerParametersAndRuleCombinerParametersAndVariableDefinitions();
        /*
         * There are at most as many combining alg parameters as policyChoiceElements.size().
         */
        final List<CombiningAlgParameter<? extends RuleEvaluator>> combiningAlgParameters = new ArrayList<>(policyChoiceElements.size());

        /*
         * Keep a copy of locally-defined variables defined in this policy, to remove them from the global manager at the end of parsing this policy. They should not be visible outside the scope of
         * this policy. There are at most as many VariableDefinitions as policyChoiceElements.size().
         */
        final List<VariableReference<?>> localVariables = new ArrayList<>(policyChoiceElements.size());

        final DefaultsType policyDefaults = policyElement.getPolicyDefaults();
        Optional<XPathCompilerProxy> childXpathCompiler;
        /*
         * Leave childXpathCompiler undefined if XPath support disabled globally.
         *
         * Else (XPath support enabled globally, but may be disabled locally for this specific Policy if XPathVersion undefined in it and any enclosing PolicySet)...
         * Reminder: According to the XACML standard, the Policy(Set)Defaults/XPathVersion must be specified (non-null) in the current Policy(Set) or any of its enclosing/ancestor PolicySet for XPath expressions to be allowed (therefore a need for a XPathCompiler), e.g. in AttributeSelectors, XPath functions, etc.
         */
        if(expressionFactory.isXPathEnabled())
        {
                /*
                If both policyDefaults and parentDefaultXPathCompiler undefined, it means no Policy(Set)Defaults/XPathVersion defined (in current Policy and any enclosing PolicySet), i.e. XPath support is disabled for this Policy, so leave childXpathCompiler undefined like parentDefaultXPathCompiler.

                We may reuse parentDefaultXPathCompiler if:
                 - parentDefaultXPathCompiler is defined
                 - AND policyDefaults/XPathVersion is undefined OR the XPath version matches the policyDefaults/XPathVersion
                 - AND namespacePrefixToUriMap is empty (i.e. only the ones from parentDefaultXPathCompiler apply)
                 */
            if (policyDefaults == null)
            {
                if(parentDefaultXPathCompiler.isEmpty() || namespacePrefixToUriMap.isEmpty()) {
                    childXpathCompiler = parentDefaultXPathCompiler;
                } else {
                    // parentDefaultXPathCompiler defined AND namespacePrefixToUriMap not empty -> new XPathCompiler to handle these new namespacePrefixToUriMap map
                    childXpathCompiler = Optional.of(new ImmutableXPathCompiler(parentDefaultXPathCompiler.get().getXPathVersion(), namespacePrefixToUriMap, List.of()));
                }
            } else {
                // policyDefaults defined
                final String xpathVersionUri = policyDefaults.getXPathVersion();
                assert xpathVersionUri != null : "PolicyDefaults(non-null)/XPathVersion = null, which violates the XACML schema! Fix: enforce XACML schema validation.";

                try
                {
                    final XPathVersion xPathVersion = XPathVersion.fromURI(xpathVersionUri);
                    if(parentDefaultXPathCompiler.isEmpty()) {
                        childXpathCompiler = Optional.of(new ImmutableXPathCompiler(xPathVersion, namespacePrefixToUriMap, List.of()));
                    } else
                        // parentDefaultXPathCompiler defined, re-use it only if XPath version matches policyDefaults and namespacePrefixToUriMap empty
                        if(parentDefaultXPathCompiler.get().getXPathVersion().equals(xPathVersion) && namespacePrefixToUriMap.isEmpty())
                        {
                            childXpathCompiler = parentDefaultXPathCompiler;
                        } else {
                            childXpathCompiler = Optional.of(new ImmutableXPathCompiler(xPathVersion, namespacePrefixToUriMap, List.of()));
                        }

                } catch (final IllegalArgumentException e)
                {
                    throw new IllegalArgumentException(policyMetadata + ": Invalid PolicySetDefaults/XPathVersion or XML namespace prefix/URI undefined", e);
                }
            }
        } else {
            // XPath support disabled globally
            childXpathCompiler = Optional.empty();
        }

        /*
        childXpathCompiler is empty iff XPath support disabled globally (!expressionFactory.isXPathEnabled()) OR (policyDefaults ==null AND parentDefaultXPathCompiler.isEmpty()), in other words iff XPath support disabled for this Policy
         */

		/*
		 If XPath support enabled, we can reuse the same XPathCompiler as long as there is no new VariableDefinition
		 */
        boolean isNewChildXpathCompilerRequired = false;

        /*
         * We keep a record of the size of the longest chain of VariableReference in this policy, and update it when a VariableDefinition occurs
         */
        int sizeOfPolicyLongestVarRefChain = 0;
        /*
         * Map to get rules by their ID so that we can resolve rules associated with RuleCombinerParameters, and detect duplicate RuleId. We want to preserve insertion order, to get map.values() in
         * order of declaration, so that ordered-* algorithms have rules in order. There are at most as many Rules as policyChoiceElements.size().
         */
        final Map<String, RuleEvaluator> ruleEvaluatorsByRuleIdInOrderOfDeclaration = new LinkedHashMap<>(policyChoiceElements.size());
        int childIndex = 0;
        for (final Serializable policyChildElt : policyChoiceElements)
        {
			/*
				 If and only if XPath enabled for this Policy (childXpathCompiler.isPresent()), XPath compiler needed for each child, can we reuse the same one as last time (it was used to create a child element evaluator) ?
				 */
            if (childXpathCompiler.isPresent() && isNewChildXpathCompilerRequired)
            {
					/*
					 New Variables defined since last XPath compiler created -> we need to use a new one to handle the new XACML Variables as XPath variables
					 */
                childXpathCompiler = Optional.of(new ImmutableXPathCompiler(childXpathCompiler.get().getXPathVersion(), namespacePrefixToUriMap, localVariables));
                isNewChildXpathCompilerRequired = false;
            }

            if (policyChildElt instanceof RuleCombinerParameters)
            {
                final String combinedRuleId = ((RuleCombinerParameters) policyChildElt).getRuleIdRef();
                final RuleEvaluator ruleEvaluator = ruleEvaluatorsByRuleIdInOrderOfDeclaration.get(combinedRuleId);
                if (ruleEvaluator == null)
                {
                    throw new IllegalArgumentException(
                            policyMetadata + ":  invalid RuleCombinerParameters: referencing undefined child Rule #" + combinedRuleId + " (no such rule defined before this element)");
                }

                final BaseCombiningAlgParameter<RuleEvaluator> combiningAlgParameter;
                try
                {
                    combiningAlgParameter = new BaseCombiningAlgParameter<>(ruleEvaluator, ((CombinerParametersType) policyChildElt).getCombinerParameters(), expressionFactory, childXpathCompiler);
                } catch (final IllegalArgumentException e)
                {
                    throw new IllegalArgumentException(policyMetadata + ": invalid child #" + childIndex + " (RuleCombinerParameters)", e);
                }

                combiningAlgParameters.add(combiningAlgParameter);
            } else if (policyChildElt instanceof CombinerParametersType)
            {
                /*
                 * CombinerParameters that is not RuleCombinerParameters already tested before
                 */
                final BaseCombiningAlgParameter<RuleEvaluator> combiningAlgParameter;
                try
                {
                    combiningAlgParameter = new BaseCombiningAlgParameter<>(null, ((CombinerParametersType) policyChildElt).getCombinerParameters(), expressionFactory, childXpathCompiler);
                } catch (final IllegalArgumentException e)
                {
                    throw new IllegalArgumentException(policyMetadata + ": invalid child #" + childIndex + " (CombinerParameters)", e);
                }

                combiningAlgParameters.add(combiningAlgParameter);
            } else if (policyChildElt instanceof VariableDefinition)
            {
                final VariableDefinition varDef = (VariableDefinition) policyChildElt;
                final Deque<String> varDefLongestVarRefChain = new ArrayDeque<>();
                final VariableReference<?> var;
                try
                {
                    var = expressionFactory.addVariable(varDef, varDefLongestVarRefChain, childXpathCompiler);
                } catch (final IllegalArgumentException e)
                {
                    throw new IllegalArgumentException(policyMetadata + ": invalid child #" + childIndex + " (VariableDefinition)", e);
                }

                if (var != null)
                {
                    /*
                     * Conflicts can occur between variables defined in this policy but also with others already in a wider scope, i.e. defined in parent/ancestor policy
                     */
                    throw new IllegalArgumentException(policyMetadata + ": Duplicable VariableDefinition for VariableId = " + var.getVariableId());
                }

                localVariables.add(expressionFactory.getVariableExpression(varDef.getVariableId()));
				/*
					 New Variables defined since last XPath compiler created -> we need to use a new one to handle the new XACML Variables as XPath variables in the subsequent child elements
					 */
                isNewChildXpathCompilerRequired = true;

                /*
                 * check whether the longest VariableReference chain in the VariableDefinition is longer than what we've got so far
                 */
                final int sizeOfVarDefLongestVarRefChain = varDefLongestVarRefChain.size();
                if (sizeOfVarDefLongestVarRefChain > sizeOfPolicyLongestVarRefChain)
                {
                    sizeOfPolicyLongestVarRefChain = sizeOfVarDefLongestVarRefChain;
                }
            } else if (policyChildElt instanceof Rule)
            {
                final RuleEvaluator ruleEvaluator;
                try
                {
                    ruleEvaluator = RuleEvaluators.getInstance((Rule) policyChildElt, expressionFactory, childXpathCompiler);
                } catch (final IllegalArgumentException e)
                {
                    throw new IllegalArgumentException(policyMetadata + ": Error parsing child #" + childIndex + " (Rule)", e);
                }

                final boolean skipRule;
                if(ruleEvaluator.getEffect() == null) {
                    // Skip rule unless debugging is enabled
                    if(LOGGER.isDebugEnabled()) {
                       skipRule = false;
                        LOGGER.warn("Rule [{}] is always NotApplicable (constant False Condition), i.e. has no effect, therefore can be removed. Keeping the rule in the policy evaluation anyway for debugging purposes (log level = DEBUG)", ruleEvaluator.getRuleId());
                    } else {
                        skipRule = true;
                        LOGGER.warn("Rule [{}] is always NotApplicable (constant False Condition), i.e. has no effect, therefore can be removed. Optimizing: the rule is removed from policy evaluation (log level != DEBUG)", ruleEvaluator.getRuleId());
                    }
                } else
                {
                    skipRule = false;
                }
                if(!skipRule) {
                    final RuleEvaluator conflictingRuleEvaluator = ruleEvaluatorsByRuleIdInOrderOfDeclaration.putIfAbsent(ruleEvaluator.getRuleId(), ruleEvaluator);
                    if (conflictingRuleEvaluator != null)
                    {
                        /*
                         * Conflict: 2 Rule elements with same RuleId -> violates uniqueness of RuleId within a Policy (XACML spec)
                         */
                        throw new IllegalArgumentException(policyMetadata + ": Duplicate Rule with RuleId = " + conflictingRuleEvaluator.getRuleId());
                    }
                }
            }

            childIndex++;
        }

        final ObligationExpressions obligationExps = policyElement.getObligationExpressions();
        final AdviceExpressions adviceExps = policyElement.getAdviceExpressions();
        final StaticTopLevelPolicyElementEvaluator policyEvaluator = new StaticBaseTopLevelPolicyElementEvaluator<>(RuleEvaluator.class, policyMetadata, Optional.empty(),
                targetEvaluator, ImmutableList.copyOf(localVariables), policyElement.getRuleCombiningAlgId(), ImmutableList.copyOf(ruleEvaluatorsByRuleIdInOrderOfDeclaration.values()), ImmutableList.copyOf(combiningAlgParameters),
                obligationExps == null ? null : obligationExps.getObligationExpressions(), adviceExps == null ? null : adviceExps.getAdviceExpressions(),
                expressionFactory, combiningAlgRegistry, childXpathCompiler);

        /*
         * We are done parsing expressions in this policy, including VariableReferences, it's time to remove variables scoped to this policy from the variable manager
         */
        localVariables.forEach(var -> expressionFactory.removeVariable(var.getVariableId()));

        return policyEvaluator;
    }

    private interface PolicyRefEvaluatorFactory<INSTANCE extends PolicyRefEvaluator>
    {

        INSTANCE getInstance(TopLevelPolicyElementType refPolicyType, String idRefPolicyId, Optional<PolicyVersionPatterns> versionConstraints, Deque<String> policySetRefChainWithIdRefIfPolicySet);
    }

    private static final class StaticPolicyRefEvaluatorFactory implements PolicyRefEvaluatorFactory<StaticPolicyRefEvaluator>
    {
        private final StaticPolicyProvider refPolicyProvider;

        private StaticPolicyRefEvaluatorFactory(final StaticPolicyProvider refPolicyProvider)
        {
            assert refPolicyProvider != null;
            this.refPolicyProvider = refPolicyProvider;
        }

        @Override
        public StaticPolicyRefEvaluator getInstance(final TopLevelPolicyElementType refPolicyType, final String refPolicyId, final Optional<PolicyVersionPatterns> versionConstraints,
                                                    final Deque<String> policySetRefChainWithRefPolicyIfPolicySet)
        {
            final StaticTopLevelPolicyElementEvaluator policy;
            try
            {
                policy = refPolicyProvider.get(refPolicyType, refPolicyId, versionConstraints, policySetRefChainWithRefPolicyIfPolicySet);
            } catch (final IndeterminateEvaluationException e)
            {
                throw new IllegalArgumentException("Error resolving statically or parsing " + PolicyRefEvaluator.toString(refPolicyType, refPolicyId, versionConstraints)
                        + " into its referenced policy (via static policy provider)", e);
            }

            if (policy == null)
            {
                throw new IllegalArgumentException("No " + refPolicyType + " matching reference: id = " + refPolicyId + ", " + versionConstraints);
            }

            return new StaticPolicyRefEvaluator(policy, versionConstraints);
        }
    }

    private static final class DynamicPolicyRefEvaluatorFactory implements PolicyRefEvaluatorFactory<PolicyRefEvaluator>
    {
        private final PolicyProvider<?> refPolicyProvider;

        private DynamicPolicyRefEvaluatorFactory(final PolicyProvider<?> refPolicyProvider)
        {
            assert refPolicyProvider != null;
            this.refPolicyProvider = refPolicyProvider;
        }

        @Override
        public PolicyRefEvaluator getInstance(final TopLevelPolicyElementType refPolicyType, final String refPolicyId, final Optional<PolicyVersionPatterns> versionConstraints,
                                              final Deque<String> policySetRefChainWithRefPolicyIfPolicySet)
        {
            // dynamic reference resolution
            if (refPolicyType == TopLevelPolicyElementType.POLICY)
            {
                return new DynamicPolicyRefEvaluator(refPolicyId, versionConstraints, refPolicyProvider);
            }

            return new DynamicPolicySetRefEvaluator(refPolicyId, versionConstraints, refPolicyProvider, policySetRefChainWithRefPolicyIfPolicySet);
        }
    }

    private static <PRE extends PolicyRefEvaluator> PRE getInstanceGeneric(final PolicyRefEvaluatorFactory<PRE> policyRefEvaluatorFactory, final TopLevelPolicyElementType refPolicyType,
                                                                           final IdReferenceType idRef, final Deque<String> policySetRefChainWithIdRefIfPolicySet) throws IllegalArgumentException
    {
        assert policyRefEvaluatorFactory != null && idRef != null;

        final PolicyVersionPatterns versionConstraints = new PolicyVersionPatterns(idRef.getVersion(), idRef.getEarliestVersion(), idRef.getLatestVersion());
        return policyRefEvaluatorFactory.getInstance(refPolicyType, idRef.getValue(), Optional.of(versionConstraints), policySetRefChainWithIdRefIfPolicySet);
    }

    /**
     * Instantiates Policy(Set) Reference evaluator from XACML Policy(Set)IdReference
     *
     * @param idRef                                 Policy(Set)IdReference
     * @param refPolicyProvider                     Policy(Set)IdReference resolver/Provider
     * @param refPolicyType                         type of policy referenced, i.e. whether it refers to Policy or PolicySet
     * @param policySetRefChainWithIdRefIfPolicySet null if {@code refPolicyType == TopLevelPolicyElementType.POLICY}; else it is the chain of PolicySets linked via PolicySetIdReferences, from the root PolicySet up to this reference
     *                                              target (last item is the {@code idRef} value). Each item is a PolicySetId of a PolicySet that is referenced by the previous item (except the first item which is the root policy) and
     *                                              references the next one. This chain is used to control PolicySetIdReferences found within the result policy, in order to detect loops (circular references) and prevent exceeding
     *                                              reference depth.
     *                                              <p>
     *                                              Beware that we only keep the IDs in the chain, and not the version, because we consider that a reference loop on the same policy ID is not allowed, no matter what the version is.
     *                                              <p>
     *                                              (Do not use a Queue for {@code ancestorPolicySetRefChain} as it is FIFO, and we need LIFO and iteration in order of insertion, so different from Collections.asLifoQueue(Deque) as
     *                                              well.)
     *                                              </p>
     * @return instance of PolicyReference
     * @throws java.lang.IllegalArgumentException if {@code refPolicyProvider} undefined, or there is no policy of type {@code refPolicyType} matching {@code idRef} to be found by {@code refPolicyProvider}, or PolicySetIdReference
     *                                            loop detected or PolicySetIdReference depth exceeds the max enforced by {@code policyProvider}
     */
    public static PolicyRefEvaluator getInstance(final TopLevelPolicyElementType refPolicyType, final IdReferenceType idRef, final PolicyProvider<?> refPolicyProvider,
                                                 final Deque<String> policySetRefChainWithIdRefIfPolicySet) throws IllegalArgumentException
    {
        final PolicyRefEvaluatorFactory<? extends PolicyRefEvaluator> factory = refPolicyProvider instanceof StaticPolicyProvider
                ? new StaticPolicyRefEvaluatorFactory((StaticPolicyProvider) refPolicyProvider)
                : new DynamicPolicyRefEvaluatorFactory(refPolicyProvider);
        return getInstanceGeneric(factory, refPolicyType, idRef, policySetRefChainWithIdRefIfPolicySet);
    }

    /**
     * Instantiates Static Policy(Set) Reference evaluator from XACML Policy(Set)IdReference, "static" meaning that given {@code idRef} and {@code refPolicyType}, the returned policy is always the
     * same statically defined policy
     *
     * @param idRef                     Policy(Set)IdReference
     * @param refPolicyProvider         Policy(Set)IdReference resolver/Provider
     * @param refPolicyType             type of policy referenced, i.e. whether it refers to Policy or PolicySet
     * @param ancestorPolicySetRefChain chain of ancestor PolicySets linked via PolicySetIdReferences, from the root PolicySet up to the Policy(Set) reference being resolved by this method (excluded). <b>Null/empty if
     *                                  {@code policyElement} this method is used to resolve the root PolicySet (no ancestor).</b> Each item is a PolicySetId of a PolicySet that is referenced by the previous item (except
     *                                  the first item which is the root policy) and references the next one. This chain is used to control PolicySetIdReferences found within the result policy, in order to detect loops
     *                                  (circular references) and prevent exceeding reference depth.
     *                                  <p>
     *                                  Beware that we only keep the IDs in the chain, and not the version, because we consider that a reference loop on the same policy ID is not allowed, no matter what the version is.
     *                                  <p>
     *                                  (Do not use a Queue for {@code ancestorPolicySetRefChain} as it is FIFO, and we need LIFO and iteration in order of insertion, so different from Collections.asLifoQueue(Deque) as
     *                                  well.)
     *                                  </p>
     * @return instance of PolicyReference
     * @throws java.lang.IllegalArgumentException if {@code refPolicyProvider} undefined, or there is no policy of type {@code refPolicyType} matching {@code idRef} to be found by {@code refPolicyProvider}, or PolicySetIdReference
     *                                            loop detected or PolicySetIdReference depth exceeds the max enforced by {@code policyProvider}
     */
    public static StaticPolicyRefEvaluator getInstanceStatic(final TopLevelPolicyElementType refPolicyType, final IdReferenceType idRef, final StaticPolicyProvider refPolicyProvider,
                                                             final Deque<String> ancestorPolicySetRefChain) throws IllegalArgumentException
    {
        final PolicyRefEvaluatorFactory<StaticPolicyRefEvaluator> factory = new StaticPolicyRefEvaluatorFactory(refPolicyProvider);
        return getInstanceGeneric(factory, refPolicyType, idRef, ancestorPolicySetRefChain);
    }

    /**
     * Creates statically defined PolicySet handler from XACML PolicySet element
     *
     * @param policyElement                                 PolicySet (XACML) without any dynamic policy references
     * @param parentDefaultXPathCompiler                    XPath compiler corresponding to parent PolicySet's default XPath version, or null if either no parent or no default XPath version defined in parent
     * @param namespacePrefixToUriMap                        namespace prefix-URI mappings from the original XACML PolicySet (XML) document, to be used for namespace-aware XPath evaluation; null or empty iff XPath support disabled
     * @param expressionFactory                             Expression factory/parser
     * @param combiningAlgorithmRegistry                    policy/rule combining algorithm registry
     * @param refPolicyProvider                             static policy-by-reference (Policy(Set)IdReference) Provider - all references statically resolved - to find references used in this policyset
     * @param policySetRefChainWithPolicyElementIfRefTarget null/empty if {@code policyElement} is a root PolicySet; else it is the chain of top-level (as opposed to nested inline) PolicySets linked via PolicySetIdReferences, from the root
     *                                                      PolicySet up to - and including - the top-level PolicySet that encloses or is a {@code policyElement} (i.e. a reference's target). Each item is a PolicySetId of a PolicySet that is
     *                                                      referenced by the previous item (except the first item which is the root policy) and references the next one. This chain is used to control PolicySetIdReferences found within the
     *                                                      result policy, in order to detect loops (circular references) and prevent exceeding reference depth.
     *                                                      <p>
     *                                                      Beware that we only keep the IDs in the chain, and not the version, because we consider that a reference loop on the same policy ID is not allowed, no matter what the version is.
     *                                                      <p>
     *                                                      (Do not use a Queue for {@code ancestorPolicySetRefChain} as it is FIFO, and we need LIFO and iteration in order of insertion, so different from Collections.asLifoQueue(Deque) as
     *                                                      well.)
     *                                                      </p>
     * @return instance
     * @throws java.lang.IllegalArgumentException if any argument (e.g. {@code policyElement}) is invalid
     */
    public static StaticTopLevelPolicyElementEvaluator getInstanceStatic(final PolicySet policyElement, final ExpressionFactory expressionFactory, final CombiningAlgRegistry combiningAlgorithmRegistry,
                                                                         final StaticPolicyProvider refPolicyProvider, final Deque<String> policySetRefChainWithPolicyElementIfRefTarget, final Optional<XPathCompilerProxy> parentDefaultXPathCompiler,
                                                                         final Map<String, String> namespacePrefixToUriMap) throws IllegalArgumentException
    {
        if (policyElement == null)
        {
            throw NULL_XACML_POLICYSET_ARG_EXCEPTION;
        }

        final PrimaryPolicyMetadata policyMetadata = new BasePrimaryPolicyMetadata(TopLevelPolicyElementType.POLICY_SET, policyElement.getPolicySetId(), new PolicyVersion(policyElement.getVersion()));
        final StaticPolicySetElementEvaluatorFactory factory = new StaticPolicySetElementEvaluatorFactory(policyMetadata, refPolicyProvider,
               expressionFactory, combiningAlgorithmRegistry,  Optional.ofNullable(policyElement.getPolicySetDefaults()), parentDefaultXPathCompiler, namespacePrefixToUriMap);
        return getInstanceGeneric(factory, policyElement, policySetRefChainWithPolicyElementIfRefTarget);
    }

    /**
     * Creates PolicySet handler from XACML PolicySet element with additional check of duplicate Policy(Set)Ids against a list of Policy(Set)s parsed during the PDP initialization so far
     *
     * @param policyElement              PolicySet (XACML)
     * @param parentDefaultXPathCompiler XPath compiler corresponding to parent PolicySet's default XPath version, or null if either no parent or no default XPath version defined in parent
     * @param namespacePrefixToUriMap     namespace prefix-URI mappings from the original XACML PolicySet (XML) document, to be used for namespace-aware XPath evaluation; null or empty iff XPath support disabled
     * @param expressionFactory          Expression factory/parser
     * @param combiningAlgorithmRegistry policy/rule combining algorithm registry
     * @param refPolicyProvider          policy-by-reference (Policy(Set)IdReference) Provider to find references used in this policyset
     * @param ancestorPolicySetRefChain  chain of ancestor PolicySets linked via PolicySetIdReferences, from the root PolicySet up to {@code policyElement} (excluded). <b>Null/empty if {@code policyElement} is the root
     *                                   PolicySet (no ancestor).</b> Each item is a PolicySetId of a PolicySet that is referenced by the previous item (except the first item which is the root policy) and references the
     *                                   next one. This chain is used to control PolicySetIdReferences found within the result policy, in order to detect loops (circular references) and prevent exceeding reference depth.
     *                                   <p>
     *                                   Beware that we only keep the IDs in the chain, and not the version, because we consider that a reference loop on the same policy ID is not allowed, no matter what the version is.
     *                                   <p>
     *                                   (Do not use a Queue for {@code ancestorPolicySetRefChain} as it is FIFO, and we need LIFO and iteration in order of insertion, so different from Collections.asLifoQueue(Deque) as
     *                                   well.)
     *                                   </p>
     * @return instance
     * @throws java.lang.IllegalArgumentException if any argument (e.g. {@code policyElement}) is invalid
     */
    public static TopLevelPolicyElementEvaluator getInstance(final PolicySet policyElement, final ExpressionFactory expressionFactory, final CombiningAlgRegistry combiningAlgorithmRegistry, final PolicyProvider<?> refPolicyProvider, final Deque<String> ancestorPolicySetRefChain, final Optional<XPathCompilerProxy> parentDefaultXPathCompiler, final Map<String, String> namespacePrefixToUriMap)
            throws IllegalArgumentException
    {
        if (policyElement == null)
        {
            throw NULL_XACML_POLICYSET_ARG_EXCEPTION;
        }

        final PrimaryPolicyMetadata policyMetadata = new BasePrimaryPolicyMetadata(TopLevelPolicyElementType.POLICY_SET, policyElement.getPolicySetId(), new PolicyVersion(policyElement.getVersion()));
        final PolicySetElementEvaluatorFactory<?, ?> factory = refPolicyProvider instanceof StaticPolicyProvider
                ? new StaticPolicySetElementEvaluatorFactory(policyMetadata, (StaticPolicyProvider) refPolicyProvider, expressionFactory, combiningAlgorithmRegistry, Optional.ofNullable(policyElement.getPolicySetDefaults()),parentDefaultXPathCompiler, namespacePrefixToUriMap)
                : new DynamicPolicySetElementEvaluatorFactory(policyMetadata, refPolicyProvider, expressionFactory, combiningAlgorithmRegistry, Optional.ofNullable(policyElement.getPolicySetDefaults()),  parentDefaultXPathCompiler, namespacePrefixToUriMap);
        return getInstanceGeneric(factory, policyElement, ancestorPolicySetRefChain);
    }

    private static abstract class PolicySetElementEvaluatorFactory<INSTANCE extends TopLevelPolicyElementEvaluator, COMBINED_ELT extends PolicyEvaluator>
    {
        protected final PrimaryPolicyMetadata policyMetadata;
        protected final Optional<XPathCompilerProxy> defaultXPathCompiler;
        protected final Map<String, String> namespacePrefixToUriMap;
        protected final ExpressionFactory expressionFactory;
        protected final CombiningAlgRegistry combiningAlgorithmRegistry;

        private PolicySetElementEvaluatorFactory(final PrimaryPolicyMetadata policyMetadata, final ExpressionFactory expressionFactory, final CombiningAlgRegistry combiningAlgorithmRegistry, final Optional<DefaultsType> policyDefaults, final Optional<XPathCompilerProxy> parentDefaultXPathCompiler,
                                                 final Map<String, String> namespacePrefixToUriMap)
        {
            assert policyMetadata != null && combiningAlgorithmRegistry != null && expressionFactory != null;
            this.policyMetadata = policyMetadata;
            this.expressionFactory = expressionFactory;
            this.combiningAlgorithmRegistry = combiningAlgorithmRegistry;
            /*
             * Leave defaultXPathCompiler undefined if XPath support disabled globally.
             *
             * Else (XPath support enabled globally, but may be disabled locally for specific Policy(Set) if XPathVersion undefined)...
             * Reminder: According to the XACML standard, the Policy(Set)Defaults/XPathVersion must be specified (non-null) in the current Policy(Set) or any of its enclosing/ancestor PolicySet for XPath expressions to be allowed (therefore a need for a XPathCompiler), e.g. in AttributeSelectors, XPath functions, etc.
             */
            if(expressionFactory.isXPathEnabled())
            {
                /*
                If both policyDefaults and parentDefaultXPathCompiler undefined, it means no PolicySetDefaults/XPathVersion defined (in current and any enclosing PolicySet), i.e. XPath support is disabled for this PolicySet, so leave defaultXPathCompiler undefined like parentDefaultXPathCompiler.

                We may reuse parentDefaultXPathCompiler if:
                 - parentDefaultXPathCompiler is defined
                 - AND policyDefaults/XPathVersion is undefined OR the XPath version matches the policyDefaults/XPathVersion
                 - AND namespacePrefixToUriMap is empty (i.e. only the ones from parentDefaultXPathCompiler apply)
                 */
                if (policyDefaults.isEmpty())
                {
                    if(parentDefaultXPathCompiler.isEmpty() || namespacePrefixToUriMap.isEmpty()) {
                        defaultXPathCompiler = parentDefaultXPathCompiler;
                    } else {
                        // parentDefaultXPathCompiler defined AND namespacePrefixToUriMap not empty -> new XPathCompiler to handle these new namespacePrefixToUriMap map
                        defaultXPathCompiler = Optional.of(new ImmutableXPathCompiler(parentDefaultXPathCompiler.get().getXPathVersion(), namespacePrefixToUriMap, List.of()));
                    }
                } else {
                    // policyDefaults defined
                    final String xpathVersionUri = policyDefaults.get().getXPathVersion();
                    assert xpathVersionUri != null : "PolicySetDefaults(non-null)/XPathVersion = null, which violates the XACML schema! Fix: enforce XACML schema validation.";

                    try
                    {
                        final XPathVersion xPathVersion = XPathVersion.fromURI(xpathVersionUri);
                        if(parentDefaultXPathCompiler.isEmpty()) {
                            defaultXPathCompiler = Optional.of(new ImmutableXPathCompiler(xPathVersion, namespacePrefixToUriMap, List.of()));
                        } else
                            // parentDefaultXPathCompiler defined, re-use it only if XPath version matches policyDefaults and namespacePrefixToUriMap empty
                            if(parentDefaultXPathCompiler.get().getXPathVersion().equals(xPathVersion) && namespacePrefixToUriMap.isEmpty())
                            {
                                defaultXPathCompiler = parentDefaultXPathCompiler;
                            } else {
                                defaultXPathCompiler = Optional.of(new ImmutableXPathCompiler(parentDefaultXPathCompiler.get().getXPathVersion(), namespacePrefixToUriMap, List.of()));
                            }

                    } catch (final IllegalArgumentException e)
                    {
                        throw new IllegalArgumentException(policyMetadata + ": Invalid PolicySetDefaults/XPathVersion or XML namespace prefix/URI undefined", e);
                    }
                }
            } else {
                // XPath support disabled globally
                this.defaultXPathCompiler = Optional.empty();
            }

            /*
            If defaultXPathCompiler is set, it is already holding the input namespacePrefixToUriMap, so no need to pass for the extra map
             */
            this.namespacePrefixToUriMap = defaultXPathCompiler.isEmpty()? namespacePrefixToUriMap: Map.of();
        }

        protected final StaticPolicyEvaluator getChildStaticPolicyEvaluator(final int childIndex, final Policy policyChildElt)
        {
            final StaticPolicyEvaluator childElement;
            try
            {
                childElement = PolicyEvaluators.getInstance(policyChildElt, expressionFactory, combiningAlgorithmRegistry, defaultXPathCompiler, namespacePrefixToUriMap);
            } catch (final IllegalArgumentException e)
            {
                throw new IllegalArgumentException(this.policyMetadata + ": invalid child #" + childIndex + " (Policy)", e);
            }

            return childElement;
        }

        protected abstract Deque<String> joinPolicySetRefChains(final Deque<String> policyRefChain1, final List<String> policyRefChain2);

        protected abstract COMBINED_ELT getChildPolicyEvaluator(int childIndex, Policy policyChildElt);

        protected abstract COMBINED_ELT getChildPolicySetEvaluator(int childIndex, PolicySet policySetChildElt, Deque<String> policySetRefChain);

        /**
         * @param childIndex                          index of this child policyRef element among all its parent's children (in order of declaration)
         * @param refPolicyType                       type of reference target (Policy or PolicySet
         * @param idRef                               policy reference
         * @param policySetRefChainWithArgIfPolicySet policySet reference chain that includes {@code idRef} value (target policyset ID) iff {@code refPolicyType == TopLevelPolicyElementType.POLICY_SET} (reference target is a
         *                                            PolicySet)
         * @return target policy evaluator
         */
        protected abstract COMBINED_ELT getChildPolicyRefEvaluator(int childIndex, TopLevelPolicyElementType refPolicyType, IdReferenceType idRef, Deque<String> policySetRefChainWithArgIfPolicySet);

        protected abstract INSTANCE getInstance(PrimaryPolicyMetadata primaryPolicyMetadata, BooleanEvaluator targetEvaluator, ImmutableList<VariableReference<?>> localVariables, String policyCombiningAlgId, ImmutableList<COMBINED_ELT> combinedElements,
                                                ImmutableList<CombiningAlgParameter<? extends COMBINED_ELT>> policyCombinerParameters, List<ObligationExpression> obligationExpressions, List<AdviceExpression> adviceExpressions);
    }

    private static final class StaticPolicySetElementEvaluatorFactory extends PolicySetElementEvaluatorFactory<StaticTopLevelPolicyElementEvaluator, StaticPolicyEvaluator>
    {
        private final StaticPolicySetChildRefsMetadataProvider extraMetadataProvider;
        private final StaticPolicyProvider refPolicyProvider;

        private StaticPolicySetElementEvaluatorFactory(final PrimaryPolicyMetadata primaryPolicyMetadata, final StaticPolicyProvider refPolicyProvider, final ExpressionFactory expressionFactory, final CombiningAlgRegistry combiningAlgorithmRegistry, final Optional<DefaultsType> policyDefaults, final Optional<XPathCompilerProxy> parentDefaultXPathCompiler, final Map<String, String> namespacePrefixToUriMap)
        {
            super(primaryPolicyMetadata, expressionFactory, combiningAlgorithmRegistry, policyDefaults, parentDefaultXPathCompiler, namespacePrefixToUriMap);
            this.extraMetadataProvider = new StaticPolicySetChildRefsMetadataProvider(primaryPolicyMetadata);
            this.refPolicyProvider = refPolicyProvider;
        }

        @Override
        protected Deque<String> joinPolicySetRefChains(final Deque<String> policyRefChain1, final List<String> policyRefChain2)
        {
            return refPolicyProvider.joinPolicyRefChains(policyRefChain1, policyRefChain2);
        }

        @Override
        protected StaticPolicyEvaluator getChildPolicyEvaluator(final int childIndex, final Policy policyChildElt)
        {
            return getChildStaticPolicyEvaluator(childIndex, policyChildElt);
        }

        @Override
        protected StaticPolicyEvaluator getChildPolicySetEvaluator(final int childIndex, final PolicySet policySetChildElt, final Deque<String> policySetRefChain)
        {
            final StaticPolicyEvaluator childElement;
            try
            {
                childElement = PolicyEvaluators.getInstanceStatic(policySetChildElt, expressionFactory, combiningAlgorithmRegistry, refPolicyProvider,
                        policySetRefChain == null ? null : new ArrayDeque<>(policySetRefChain), defaultXPathCompiler, namespacePrefixToUriMap);
            } catch (final IllegalArgumentException e)
            {
                throw new IllegalArgumentException(this.policyMetadata + ": Invalid child #" + childIndex + " (PolicySet)", e);
            }

            /*
             * This child PolicySet may have extra metadata such as nested policy references that we need to merge into the parent PolicySet's metadata
             */
            final Optional<PolicyRefsMetadata> childPolicyRefsMetadata = childElement.getPolicyRefsMetadata();
            childPolicyRefsMetadata.ifPresent(extraMetadataProvider::updateMetadata);

            return childElement;
        }

        @Override
        protected StaticPolicyEvaluator getChildPolicyRefEvaluator(final int childIndex, final TopLevelPolicyElementType refPolicyType, final IdReferenceType idRef,
                                                                   final Deque<String> ancestorPolicySetRefChain)
        {
            if (refPolicyProvider == null)
            {
                throw new IllegalArgumentException(this.policyMetadata + ": invalid child #" + childIndex
                        + " (PolicyIdReference): no refPolicyProvider (module responsible for resolving Policy(Set)IdReferences) defined to support it.");
            }

            final StaticPolicyRefEvaluator childElement = PolicyEvaluators.getInstanceStatic(refPolicyType, idRef, refPolicyProvider, ancestorPolicySetRefChain);
            final Optional<PolicyRefsMetadata> childPolicyRefsMetadata = childElement.getPolicyRefsMetadata();
            childPolicyRefsMetadata.ifPresent(extraMetadataProvider::updateMetadata);

            return childElement;
        }

        @Override
        protected StaticTopLevelPolicyElementEvaluator getInstance(final PrimaryPolicyMetadata primaryPolicyMetadata, final BooleanEvaluator policyTargetEvaluator, final ImmutableList<VariableReference<?>> localVariables, final String policyCombiningAlgId,
                                                                   final ImmutableList<StaticPolicyEvaluator> combinedElements, final ImmutableList<CombiningAlgParameter<? extends StaticPolicyEvaluator>> policyCombinerParameters,
                                                                   final List<ObligationExpression> obligationExpressions, final List<AdviceExpression> adviceExpressions)
        {
            return new StaticBaseTopLevelPolicyElementEvaluator<>(StaticPolicyEvaluator.class, primaryPolicyMetadata, extraMetadataProvider.getMetadata(), policyTargetEvaluator, localVariables, policyCombiningAlgId,
                    combinedElements, policyCombinerParameters, obligationExpressions, adviceExpressions, expressionFactory, combiningAlgorithmRegistry, defaultXPathCompiler);
        }
    }

    private static final class DynamicPolicySetElementEvaluatorFactory extends PolicySetElementEvaluatorFactory<TopLevelPolicyElementEvaluator, PolicyEvaluator>
    {
        private final DynamicPolicySetChildRefsMetadataProvider extraMetadataProvider;
        private final PolicyProvider<?> refPolicyProvider;

        private DynamicPolicySetElementEvaluatorFactory(final PrimaryPolicyMetadata primaryPolicyMetadata, final PolicyProvider<?> refPolicyProvider,
                                                        final ExpressionFactory expressionFactory,
                                                        final CombiningAlgRegistry combiningAlgorithmRegistry, final Optional<DefaultsType> policyDefaults, final Optional<XPathCompilerProxy> parentDefaultXPathCompiler, final Map<String, String> namespacePrefixToUriMap)
        {
            super(primaryPolicyMetadata, expressionFactory, combiningAlgorithmRegistry, policyDefaults, parentDefaultXPathCompiler, namespacePrefixToUriMap);
            this.extraMetadataProvider = new DynamicPolicySetChildRefsMetadataProvider();
            this.refPolicyProvider = refPolicyProvider;
        }

        @Override
        protected Deque<String> joinPolicySetRefChains(final Deque<String> policyRefChain1, final List<String> policyRefChain2)
        {
            return refPolicyProvider.joinPolicyRefChains(policyRefChain1, policyRefChain2);
        }

        @Override
        protected PolicyEvaluator getChildPolicyEvaluator(final int childIndex, final Policy policyChildElt)
        {
            return getChildStaticPolicyEvaluator(childIndex, policyChildElt);
        }

        @Override
        protected PolicyEvaluator getChildPolicySetEvaluator(final int childIndex, final PolicySet policySetChildElt, final Deque<String> policySetRefChain)
        {
            final PolicyEvaluator childElement;
            try
            {
                childElement = PolicyEvaluators.getInstance(policySetChildElt, expressionFactory, combiningAlgorithmRegistry, refPolicyProvider,
                        policySetRefChain == null ? null : new ArrayDeque<>(policySetRefChain), defaultXPathCompiler, namespacePrefixToUriMap);
            } catch (final IllegalArgumentException e)
            {
                throw new IllegalArgumentException(this.policyMetadata + ": Invalid child #" + childIndex + " (PolicySet)", e);
            }

            /*
             * This child PolicySet may have extra metadata such as nested policy references that we need to merge into the parent PolicySet's metadata
             */
            extraMetadataProvider.addChildPolicySetElementOrRef(childElement);
            return childElement;
        }

        @Override
        protected PolicyEvaluator getChildPolicyRefEvaluator(final int childIndex, final TopLevelPolicyElementType refPolicyType, final IdReferenceType idRef,
                                                             final Deque<String> policySetRefChainWithArgIfPolicySet)
        {
            if (refPolicyProvider == null)
            {
                throw new IllegalArgumentException(this.policyMetadata + ": invalid child #" + childIndex
                        + " (PolicyIdReference): no refPolicyProvider (module responsible for resolving Policy(Set)IdReferences) defined to support it.");
            }

            final PolicyRefEvaluator childElement = PolicyEvaluators.getInstance(refPolicyType, idRef, refPolicyProvider, policySetRefChainWithArgIfPolicySet);
            extraMetadataProvider.addChildPolicySetElementOrRef(childElement);
            return childElement;
        }

        @Override
        protected TopLevelPolicyElementEvaluator getInstance(final PrimaryPolicyMetadata primaryPolicyMetadata, final BooleanEvaluator policyTargetEvaluator, final ImmutableList<VariableReference<?>> localVariables, final String policyCombiningAlgId,
                                                             final ImmutableList<PolicyEvaluator> combinedElements, final ImmutableList<CombiningAlgParameter<? extends PolicyEvaluator>> policyCombinerParameters,
                                                             final List<ObligationExpression> obligationExpressions, final List<AdviceExpression> adviceExpressions)
        {
            return new DynamicPolicySetEvaluator(primaryPolicyMetadata, extraMetadataProvider, policyTargetEvaluator, localVariables, policyCombiningAlgId, combinedElements, policyCombinerParameters, obligationExpressions,
                    adviceExpressions, expressionFactory, combiningAlgorithmRegistry, defaultXPathCompiler);
        }
    }

    /**
     * Generic creation of PolicySet evaluator
     *
     * @param policySetRefChainWithArgIffRefTarget null/empty if {@code policyElement} is the root policySet; else it is the chain of top-level (as opposed to nested inline) PolicySets linked by PolicySetIdReferences from the root
     *                                             PolicySet up to (and including) the top-level (PolicySetIdReference-targeted) PolicySet that encloses or is {@code policyElement}
     */
    private static <TLPEE extends TopLevelPolicyElementEvaluator, COMBINED_EVALUATOR extends PolicyEvaluator> TLPEE getInstanceGeneric(
            final PolicySetElementEvaluatorFactory<TLPEE, COMBINED_EVALUATOR> policyEvaluatorFactory, final PolicySet policyElement, final Deque<String> policySetRefChainWithArgIffRefTarget)
            throws IllegalArgumentException
    {
        assert policyEvaluatorFactory != null && policyElement != null;

        // final Set<PrimaryPolicyMetadata> enclosedPolicies = HashCollections.newUpdatableSet();

        final String policyId = policyElement.getPolicySetId();

        final BooleanEvaluator targetEvaluator = TargetEvaluators.getInstance(policyElement.getTarget(), policyEvaluatorFactory.expressionFactory, policyEvaluatorFactory.defaultXPathCompiler);

        /*
         * Elements defined in xs:choice of PolicySetType in XACML schema (Policy(Set)/Policy(Set)IdReference/CombinerParameters/Policy(Set)CombinerParameters
         */
        final List<Serializable> jaxbPolicySetChoiceElements = policyElement.getPolicySetsAndPoliciesAndPolicySetIdReferences();
        /*
         * Prepare the list of evaluators combined by the combining algorithm in this PolicySet, i.e. Policy(Set)/Policy(Set)IdReference evaluators. combinedEvaluators.size() <=
         * jaxbPolicySetChoiceElements.size() since combinedEvaluators does not include *CombinerParameter evaluators
         */
        final List<COMBINED_EVALUATOR> combinedEvaluators = new ArrayList<>(jaxbPolicySetChoiceElements.size());

        /*
         * Why isn't there any VariableDefinition in XACML PolicySet like in Policy? If there were, we would keep a copy of variable IDs defined in this policy, to remove them from the global manager
         * at the end of parsing this PolicySet. They should not be visible outside the scope of this.
         * <p>
         * final Set<String> variableIds = HashCollections.newUpdatableSet(jaxbPolicySetChoiceElements.size());
         */

        /*
         * Map to get child Policies by their ID so that we can resolve Policies associated with PolicyCombinerParameters Size cannot get bigger than jaxbPolicySetChoiceElements.size()
         */
        final Map<String, COMBINED_EVALUATOR> childPolicyEvaluatorsByPolicyId = HashCollections.newUpdatableMap(jaxbPolicySetChoiceElements.size());

        /*
         * Map to get child PolicySets by their ID so that we can resolve PolicySets associated with PolicySetCombinerParameters Size cannot get bigger than jaxbPolicySetChoiceElements.size()
         */
        final Map<String, COMBINED_EVALUATOR> childPolicySetEvaluatorsByPolicySetId = HashCollections.newUpdatableMap(jaxbPolicySetChoiceElements.size());

        /*
         * *CombinerParameters (combining algorithm parameters), size <= jaxbPolicySetChoiceElements.size()
         */
        final List<CombiningAlgParameter<? extends COMBINED_EVALUATOR>> combiningAlgParameters = new ArrayList<>(jaxbPolicySetChoiceElements.size());
        int childIndex = 0;
        for (final Serializable policyChildElt : jaxbPolicySetChoiceElements)
        {
            if (policyChildElt instanceof PolicyCombinerParameters)
            {
                final String combinedPolicyId = ((PolicyCombinerParameters) policyChildElt).getPolicyIdRef();
                final COMBINED_EVALUATOR childPolicyEvaluator = childPolicyEvaluatorsByPolicyId.get(combinedPolicyId);
                if (childPolicyEvaluator == null)
                {
                    throw new IllegalArgumentException(policyEvaluatorFactory.policyMetadata + ":  invalid PolicyCombinerParameters: referencing undefined child Policy #" + combinedPolicyId
                            + " (no such policy defined before this element)");
                }

                final BaseCombiningAlgParameter<COMBINED_EVALUATOR> combiningAlgParameter;
                try
                {
                    combiningAlgParameter = new BaseCombiningAlgParameter<>(childPolicyEvaluator, ((CombinerParametersType) policyChildElt).getCombinerParameters(),
                            policyEvaluatorFactory.expressionFactory, policyEvaluatorFactory.defaultXPathCompiler);
                } catch (final IllegalArgumentException e)
                {
                    throw new IllegalArgumentException(policyEvaluatorFactory.policyMetadata + ": invalid child #" + childIndex + " (PolicyCombinerParameters)", e);
                }

                combiningAlgParameters.add(combiningAlgParameter);

            } else if (policyChildElt instanceof PolicySetCombinerParameters)
            {
                final String combinedPolicySetId = ((PolicySetCombinerParameters) policyChildElt).getPolicySetIdRef();
                final COMBINED_EVALUATOR combinedPolicySetEvaluator = childPolicySetEvaluatorsByPolicySetId.get(combinedPolicySetId);
                if (combinedPolicySetEvaluator == null)
                {
                    throw new IllegalArgumentException(policyEvaluatorFactory.policyMetadata + ":  invalid PolicySetCombinerParameters: referencing undefined child PolicySet #" + combinedPolicySetId
                            + " (no such policySet defined before this element)");
                }

                final BaseCombiningAlgParameter<COMBINED_EVALUATOR> combiningAlgParameter;
                try
                {
                    combiningAlgParameter = new BaseCombiningAlgParameter<>(combinedPolicySetEvaluator, ((CombinerParametersType) policyChildElt).getCombinerParameters(),
                            policyEvaluatorFactory.expressionFactory, policyEvaluatorFactory.defaultXPathCompiler);
                } catch (final IllegalArgumentException e)
                {
                    throw new IllegalArgumentException(policyEvaluatorFactory.policyMetadata + ": invalid child #" + childIndex + " (PolicySetCombinerParameters)", e);
                }

                combiningAlgParameters.add(combiningAlgParameter);
            } else if (policyChildElt instanceof JAXBElement)
            {
                final JAXBElement<?> jaxbPolicyChildElt = (JAXBElement<?>) policyChildElt;
                final String eltNameLocalPart = jaxbPolicyChildElt.getName().getLocalPart();
                if (eltNameLocalPart.equals(XacmlNodeName.POLICY_ID_REFERENCE.value()))
                {
                    final IdReferenceType policyChildIdRef = (IdReferenceType) jaxbPolicyChildElt.getValue();
                    final COMBINED_EVALUATOR childEvaluator = policyEvaluatorFactory.getChildPolicyRefEvaluator(childIndex, TopLevelPolicyElementType.POLICY, policyChildIdRef, null);
                    combinedEvaluators.add(childEvaluator);
                    final COMBINED_EVALUATOR duplicate = childPolicySetEvaluatorsByPolicySetId.putIfAbsent(childEvaluator.getPolicyId(), childEvaluator);
                    if (duplicate != null)
                    {
                        throw new IllegalArgumentException("Duplicate PolicyIdReference's id = " + childEvaluator.getPolicyId());
                    }
                } else if (eltNameLocalPart.equals(XacmlNodeName.POLICYSET_ID_REFERENCE.value()))
                {
                    final IdReferenceType policyChildIdRef = (IdReferenceType) jaxbPolicyChildElt.getValue();
                    final String policyChildId = policyChildIdRef.getValue();
                    /*
                     * Add this new reference to policyChildIdRef to the policyRef chain arg of getChildPolicyRefEvaluator(...). If policySetRefChainWithArgIffRefTarget is null/empty, policyElement is
                     * the root policy (no ancestor in the chain), therefore it should be added before policyChildIdRef, as the antecedent; Else non-empty policySetRefChainWithArgIffRefTarget's last
                     * item is either policyElement (iff it is a policy ref's target) or the top-level (as opposed to nested inline) PolicySet that encloses policyElement, in which either case we just
                     * add policyChildIdRef to the chain.
                     */
                    final Deque<String> newPolicySetRefChainWithArgIffRefTarget = policySetRefChainWithArgIffRefTarget == null || policySetRefChainWithArgIffRefTarget.isEmpty()
                            ? new ArrayDeque<>(Arrays.asList(policyId, policyChildId))
                            : policyEvaluatorFactory.joinPolicySetRefChains(policySetRefChainWithArgIffRefTarget, Collections.singletonList(policyChildId));
                    final COMBINED_EVALUATOR childEvaluator = policyEvaluatorFactory.getChildPolicyRefEvaluator(childIndex, TopLevelPolicyElementType.POLICY_SET, policyChildIdRef,
                            newPolicySetRefChainWithArgIffRefTarget);
                    combinedEvaluators.add(childEvaluator);
                    final COMBINED_EVALUATOR duplicate = childPolicySetEvaluatorsByPolicySetId.put(policyChildId, childEvaluator);
                    if (duplicate != null)
                    {
                        throw new IllegalArgumentException("Duplicate PolicySetIdReference's id = " + policyChildId);
                    }
                } else if (eltNameLocalPart.equals(XacmlNodeName.COMBINER_PARAMETERS.value()))
                {
                    /*
                     * CombinerParameters that is not Policy(Set)CombinerParameters already tested before
                     */
                    final BaseCombiningAlgParameter<COMBINED_EVALUATOR> combiningAlgParameter;
                    try
                    {
                        combiningAlgParameter = new BaseCombiningAlgParameter<>(null, ((CombinerParametersType) jaxbPolicyChildElt.getValue()).getCombinerParameters(),
                                policyEvaluatorFactory.expressionFactory, policyEvaluatorFactory.defaultXPathCompiler);
                    } catch (final IllegalArgumentException e)
                    {
                        throw new IllegalArgumentException(policyEvaluatorFactory.policyMetadata + ": invalid child #" + childIndex + " (CombinerParameters)", e);
                    }

                    combiningAlgParameters.add(combiningAlgParameter);
                }
            } else if (policyChildElt instanceof PolicySet)
            {
                final PolicySet childPolicy = (PolicySet) policyChildElt;
                /*
                 * XACML spec §5.1: "ensure that no two policies visible to the PDP have the same identifier"
                 */
                final String childPolicyId = childPolicy.getPolicySetId();
                /*
                 * Create/Update the policySet ref chain if necessary. If policySetRefChainWithArgIffRefTarget is null/empty, this means policyElement is the root policyset (no antecedent), and we
                 * create a chain with its ID, to know the antecedent of the next encountered policyset ref (which may be found deep under multiple levels of nested PolicySets).; else
                 * policySetRefChainWithArgIffRefTarget's last item is either policyElement (iff it is a policy ref's target) or the top-level (as opposed to nested inline) PolicySet that encloses
                 * policyElement, in which either case we already have the info we need in the chain so keep it as is.
                 */
                final Deque<String> newPolicySetRefChain = policySetRefChainWithArgIffRefTarget == null || policySetRefChainWithArgIffRefTarget.isEmpty()
                        ? new ArrayDeque<>(Collections.singletonList(policyId))
                        : policySetRefChainWithArgIffRefTarget;
                final COMBINED_EVALUATOR childEvaluator = policyEvaluatorFactory.getChildPolicySetEvaluator(childIndex, childPolicy, newPolicySetRefChain);
                combinedEvaluators.add(childEvaluator);
                final COMBINED_EVALUATOR duplicate = childPolicySetEvaluatorsByPolicySetId.putIfAbsent(childPolicyId, childEvaluator);
                if (duplicate != null)
                {
                    throw new IllegalArgumentException("Duplicate PolicySetId = " + childPolicyId);
                }
            } else if (policyChildElt instanceof Policy)
            {
                final Policy childPolicy = (Policy) policyChildElt;
                /*
                 * XACML spec §5.1: "ensure that no two policies visible to the PDP have the same identifier"
                 */
                final String childPolicyId = childPolicy.getPolicyId();
                final COMBINED_EVALUATOR childEvaluator = policyEvaluatorFactory.getChildPolicyEvaluator(childIndex, childPolicy);
                combinedEvaluators.add(childEvaluator);
                final COMBINED_EVALUATOR duplicate = childPolicyEvaluatorsByPolicyId.putIfAbsent(childPolicyId, childEvaluator);
                if (duplicate != null)
                {
                    throw new IllegalArgumentException("Duplicate PolicyId = " + childPolicyId);
                }
            }

            /*
             * Why isn't there any VariableDefinition in XACML PolicySet defined by OASIS XACML 3.0 spec, like in Policy? If there were, the following code would be used (same as in PolicyEvaluator
             * class).
             */
            // else if (policySetChildElt instanceof VariableDefinition)
            // {
            // final VariableDefinition varDef = (VariableDefinition)
            // policyChildElt;
            // final Deque<String> varDefLongestVarRefChain = new
            // ArrayDeque<>();
            // final VariableReference<?> var;
            // try
            // {
            // var = expressionFactory.addVariable(varDef, defaultXPathCompiler,
            // varDefLongestVarRefChain);
            // } catch (IllegalArgumentException e)
            // {
            // throw new IllegalArgumentException(policyFriendlyId + ": invalid
            // child #" + childIndex + " (VariableDefinition)", e);
            // }
            //
            // if (var != null)
            // {
            // /*
            // * Conflicts can occur between variables defined in this policy
            // but also with others already in a wider scope, i.e. defined in
            // * parent/ancestor policy
            // */
            // throw new IllegalArgumentException(policyFriendlyId + ":
            // Duplicable VariableDefinition for VariableId=" +
            // var.getVariableId());
            // }
            //
            // localVariableIds.add(varDef.getVariableId());
            // // check whether the longest VariableReference chain in the
            // VariableDefinition is longer than what we've got so far
            // final int sizeOfVarDefLongestVarRefChain =
            // varDefLongestVarRefChain.size();
            // if(sizeOfVarDefLongestVarRefChain >
            // sizeOfPolicyLongestVarRefChain) {
            // sizeOfPolicyLongestVarRefChain = sizeOfVarDefLongestVarRefChain;
            // }
            // }

            childIndex++;
        }

        /*
         * Why isn't there any VariableDefinition in XACML PolicySet like in Policy? If there were, the final following code would be used: We are done parsing expressions in this policy, including
         * VariableReferences, it's time to remove variables scoped to this policy from the variable manager
         */
        // for (final String varId : variableIds)
        // {
        // expFactory.remove(varId);
        // }

        final ObligationExpressions obligationExps = policyElement.getObligationExpressions();
        final AdviceExpressions adviceExps = policyElement.getAdviceExpressions();
        return policyEvaluatorFactory.getInstance(policyEvaluatorFactory.policyMetadata, targetEvaluator, ImmutableList.of(), policyElement.getPolicyCombiningAlgId(), ImmutableList.copyOf(combinedEvaluators), ImmutableList.copyOf(combiningAlgParameters),
                obligationExps == null ? null : obligationExps.getObligationExpressions(), adviceExps == null ? null : adviceExps.getAdviceExpressions());
    }

}
