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
package org.ow2.authzforce.core.pdp.testutil.test.pep.cxf;

import oasis.names.tc.xacml._3_0.core.schema.wd_17.DecisionType;
import org.apache.cxf.interceptor.Fault;
import org.apache.cxf.interceptor.security.AccessDeniedException;
import org.apache.cxf.message.Message;
import org.apache.cxf.phase.AbstractPhaseInterceptor;
import org.apache.cxf.phase.Phase;
import org.apache.cxf.rt.security.saml.xacml.CXFMessageParser;
import org.apache.cxf.rt.security.saml.xacml.XACMLConstants;
import org.apache.cxf.security.LoginSecurityContext;
import org.apache.cxf.security.SecurityContext;
import org.apache.wss4j.common.ext.WSSecurityException;
import org.ow2.authzforce.core.pdp.api.*;
import org.ow2.authzforce.core.pdp.api.value.*;
import org.ow2.authzforce.core.pdp.impl.BasePdpEngine;
import org.ow2.authzforce.xacml.identifiers.XacmlAttributeId;
import org.slf4j.LoggerFactory;

import javax.xml.namespace.QName;
import java.security.Principal;
import java.util.Collections;
import java.util.Optional;
import java.util.Set;

import static org.ow2.authzforce.xacml.identifiers.XacmlAttributeCategory.*;

/**
 * This class represents a so-called XACML PEP that, for every CXF service request, creates an XACML 3.0 authorization decision Request to a PDP using AuthzForce's native API, given a Principal, list
 * of roles - typically coming from SAML token - and MessageContext. The principal name is inserted as the Subject ID, and the list of roles associated with that principal are inserted as Subject
 * roles. The action to send defaults to "execute". It is an adaptation of
 * <a href="https://github.com/coheigea/testcases/blob/master/apache/cxf/cxf-sts-xacml/src/test/java/org/apache/coheigea/cxf/sts/xacml/authorization/xacml3/XACML3AuthorizingInterceptor.java">XACML3AuthorizingInterceptor class from Apache CXF tests</a>, except it uses
 * AuthzForce native API for PDP evaluation instead of OpenAZ API.
 * <p>
 * For a SOAP Service, the resource-id Attribute refers to the "{serviceNamespace}serviceName#{operationNamespace}operationName" String (shortened to "{serviceNamespace}serviceName#operationName" if
 * the namespaces are identical). The "{serviceNamespace}serviceName", "{operationNamespace}operationName" and resource URI are also sent to simplify processing at the PDP side.
 * <p>
 * For a REST service the request URL is the resource. You can also configure the ability to send the truncated request URI instead for a SOAP or REST service.
 */
public class EmbeddedPdpBasedAuthzInterceptor extends AbstractPhaseInterceptor<Message>
{

	private static final org.slf4j.Logger LOGGER = LoggerFactory.getLogger(EmbeddedPdpBasedAuthzInterceptor.class);

	private static final String defaultSOAPAction = "execute";

	private final BasePdpEngine pdp;

	/**
	 * Create Authorization interceptor (XACML PEP) using input {@code pdp} as XACML PDP
	 * 
	 * @param pdp
	 *            XACML PDP
	 */
	public EmbeddedPdpBasedAuthzInterceptor(final BasePdpEngine pdp)
	{
		super(Phase.PRE_INVOKE);
		this.pdp = pdp;
	}

	@Override
	public void handleMessage(final Message message) throws Fault
	{
		final SecurityContext sc = message.get(SecurityContext.class);
		if (sc instanceof LoginSecurityContext)
		{
			final Principal principal = sc.getUserPrincipal();
			final LoginSecurityContext loginSecurityContext = (LoginSecurityContext) sc;
			final Set<Principal> principalRoles = loginSecurityContext.getUserRoles();
			final Set<String> roles;
			if (principalRoles == null)
			{
				roles = Collections.emptySet();
			}
			else
			{
				roles = HashCollections.newUpdatableSet(principalRoles.size());
				for (final Principal p : principalRoles)
				{
					if (p != principal)
					{
						roles.add(p.getName());
					}
				}
			}

			try
			{
				if (authorize(principal, roles, message))
				{
					return;
				}
			}
			catch (final Exception e)
			{
				LOGGER.debug("Unauthorized", e);
				throw new AccessDeniedException("Unauthorized");
			}
		}
		else
		{
			LOGGER.debug("The SecurityContext was not an instance of LoginSecurityContext. No authorization is possible as a result");
		}

		throw new AccessDeniedException("Unauthorized");
	}

	protected boolean authorize(final Principal principal, final Set<String> roles, final Message message) throws Exception
	{
		final DecisionRequest request = createRequest(principal, roles, message);
		LOGGER.debug("XACML Request: {}", request);

		// Evaluate the request
		final DecisionResult result = pdp.evaluate(request);

		if (result == null)
		{
			return false;
		}

		// Handle any Obligations returned by the PDP
		handleObligationsOrAdvice(request, principal, message, result);

		LOGGER.debug("XACML authorization result: {}", result);
		return result.getDecision() == DecisionType.PERMIT;
	}

	private DecisionRequest createRequest(final Principal principal, final Set<String> roles, final Message message) throws WSSecurityException
	{
		assert roles != null;

		final CXFMessageParser messageParser = new CXFMessageParser(message);
		final String issuer = messageParser.getIssuer();

		/*
		 * 3 attribute categories, 7 total attributes
		 */
		final DecisionRequestBuilder<?> requestBuilder = pdp.newRequestBuilder(3, 7);

		// Subject ID
		final AttributeFqn subjectIdAttributeId = AttributeFqns.newInstance(XACML_1_0_ACCESS_SUBJECT.value(), Optional.ofNullable(issuer), XacmlAttributeId.XACML_1_0_SUBJECT_ID.value());
		final AttributeBag<?> subjectIdAttributeValues = Bags.singletonAttributeBag(StandardDatatypes.STRING, new StringValue(principal.getName()));
		requestBuilder.putNamedAttributeIfAbsent(subjectIdAttributeId, subjectIdAttributeValues);

		// Subject role(s)
		final AttributeFqn subjectRoleAttributeId = AttributeFqns.newInstance(XACML_1_0_ACCESS_SUBJECT.value(), Optional.ofNullable(issuer), XacmlAttributeId.XACML_2_0_SUBJECT_ROLE.value());
		requestBuilder.putNamedAttributeIfAbsent(subjectRoleAttributeId, stringsToAnyURIBag(roles));

		// Resource ID
		final AttributeFqn resourceIdAttributeId = AttributeFqns.newInstance(XACML_3_0_RESOURCE.value(), Optional.empty(), XacmlAttributeId.XACML_1_0_RESOURCE_ID.value());
		final AttributeBag<?> resourceIdAttributeValues = Bags.singletonAttributeBag(StandardDatatypes.STRING, new StringValue(getResourceId(messageParser)));
		requestBuilder.putNamedAttributeIfAbsent(resourceIdAttributeId, resourceIdAttributeValues);

		// Resource - WSDL-defined Service ID / Operation / Endpoint
		if (messageParser.isSOAPService())
		{
			// WSDL Service
			final QName wsdlService = messageParser.getWSDLService();
			if (wsdlService != null)
			{
				final AttributeFqn resourceServiceIdAttributeId = AttributeFqns.newInstance(XACML_3_0_RESOURCE.value(), Optional.empty(), XACMLConstants.RESOURCE_WSDL_SERVICE_ID);
				final AttributeBag<?> resourceServiceIdAttributeValues = Bags.singletonAttributeBag(StandardDatatypes.STRING, new StringValue(wsdlService.toString()));
				requestBuilder.putNamedAttributeIfAbsent(resourceServiceIdAttributeId, resourceServiceIdAttributeValues);
			}

			// WSDL Operation
			final QName wsdlOperation = messageParser.getWSDLOperation();
			final AttributeFqn resourceOperationIdAttributeId = AttributeFqns.newInstance(XACML_3_0_RESOURCE.value(), Optional.empty(), XACMLConstants.RESOURCE_WSDL_OPERATION_ID);
			final AttributeBag<?> resourceOperationIddAttributeValues = Bags.singletonAttributeBag(StandardDatatypes.STRING, new StringValue(wsdlOperation.toString()));
			requestBuilder.putNamedAttributeIfAbsent(resourceOperationIdAttributeId, resourceOperationIddAttributeValues);

			// WSDL Endpoint
			final String endpointURI = messageParser.getResourceURI(false);
			final AttributeFqn resourceWSDLEndpointAttributeId = AttributeFqns.newInstance(XACML_3_0_RESOURCE.value(), Optional.empty(), XACMLConstants.RESOURCE_WSDL_ENDPOINT);
			final AttributeBag<?> resourceWSDLEndpointAttributeValues = Bags.singletonAttributeBag(StandardDatatypes.STRING, new StringValue(endpointURI));
			requestBuilder.putNamedAttributeIfAbsent(resourceWSDLEndpointAttributeId, resourceWSDLEndpointAttributeValues);
		}

		// Action ID
		final String actionToUse = messageParser.getAction(defaultSOAPAction);
		final AttributeFqn actionIdAttributeId = AttributeFqns.newInstance(XACML_3_0_ACTION.value(), Optional.empty(), XacmlAttributeId.XACML_1_0_ACTION_ID.value());
		final AttributeBag<?> actionIdAttributeValues = Bags.singletonAttributeBag(StandardDatatypes.STRING, new StringValue(actionToUse));
		requestBuilder.putNamedAttributeIfAbsent(actionIdAttributeId, actionIdAttributeValues);

		// Environment - current date/time will be set by the PDP
		return requestBuilder.build(false);
	}

	private static AttributeBag<?> stringsToAnyURIBag(final Set<String> strings)
	{
		assert strings != null;

		final Set<AnyUriValue> anyURIs = HashCollections.newUpdatableSet(strings.size());
		for (final String string : strings)
		{
			anyURIs.add(new AnyUriValue(string));
		}

		return Bags.newAttributeBag(StandardDatatypes.ANYURI, anyURIs);
	}

	private static String getResourceId(final CXFMessageParser messageParser)
	{
		final String resourceId;
		if (messageParser.isSOAPService())
		{
			final QName serviceName = messageParser.getWSDLService();
			final QName operationName = messageParser.getWSDLOperation();

			if (serviceName != null)
			{
				final String resourceIdPrefix = serviceName + "#";
				if (serviceName.getNamespaceURI() != null && serviceName.getNamespaceURI().equals(operationName.getNamespaceURI()))
				{
					resourceId = resourceIdPrefix + operationName.getLocalPart();
				}
				else
				{
					resourceId = resourceIdPrefix + operationName.toString();
				}
			}
			else
			{
				resourceId = operationName.toString();
			}
		}
		else
		{
			resourceId = messageParser.getResourceURI(false);
		}

		return resourceId;
	}

	/**
	 * Handle any Obligations returned by the PDP. Does nothing by default. Override this method if you want to handle Obligations/Advice in a specific way
	 */
	protected void handleObligationsOrAdvice(final DecisionRequest request, final Principal principal, final Message message, final DecisionResult result)
	{
		// Do nothing by default
	}

}
