/**
 *
 *  Copyright 2003-2004 Sun Microsystems, Inc. All Rights Reserved.
 *
 *  Redistribution and use in source and binary forms, with or without
 *  modification, are permitted provided that the following conditions are met:
 *
 *    1. Redistribution of source code must retain the above copyright notice,
 *       this list of conditions and the following disclaimer.
 *
 *    2. Redistribution in binary form must reproduce the above copyright
 *       notice, this list of conditions and the following disclaimer in the
 *       documentation and/or other materials provided with the distribution.
 *
 *  Neither the name of Sun Microsystems, Inc. or the names of contributors may
 *  be used to endorse or promote products derived from this software without
 *  specific prior written permission.
 *
 *  This software is provided "AS IS," without a warranty of any kind. ALL
 *  EXPRESS OR IMPLIED CONDITIONS, REPRESENTATIONS AND WARRANTIES, INCLUDING
 *  ANY IMPLIED WARRANTY OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE
 *  OR NON-INFRINGEMENT, ARE HEREBY EXCLUDED. SUN MICROSYSTEMS, INC. ("SUN")
 *  AND ITS LICENSORS SHALL NOT BE LIABLE FOR ANY DAMAGES SUFFERED BY LICENSEE
 *  AS A RESULT OF USING, MODIFYING OR DISTRIBUTING THIS SOFTWARE OR ITS
 *  DERIVATIVES. IN NO EVENT WILL SUN OR ITS LICENSORS BE LIABLE FOR ANY LOST
 *  REVENUE, PROFIT OR DATA, OR FOR DIRECT, INDIRECT, SPECIAL, CONSEQUENTIAL,
 *  INCIDENTAL OR PUNITIVE DAMAGES, HOWEVER CAUSED AND REGARDLESS OF THE THEORY
 *  OF LIABILITY, ARISING OUT OF THE USE OF OR INABILITY TO USE THIS SOFTWARE,
 *  EVEN IF SUN HAS BEEN ADVISED OF THE POSSIBILITY OF SUCH DAMAGES.
 *
 *  You acknowledge that this software is not designed or intended for use in
 *  the design, construction, operation or maintenance of any nuclear facility.
 */
package com.sun.xacml.finder.impl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import com.sun.xacml.EvaluationCtx;
import com.sun.xacml.PolicyMetaData;
import com.sun.xacml.attr.BagAttribute;
import com.sun.xacml.attr.xacmlv3.AttributeValue;
import com.sun.xacml.cond.xacmlv3.EvaluationResult;
import com.sun.xacml.ctx.Status;
import com.sun.xacml.finder.AttributeFinderModule;
import com.thalesgroup.authz.model.ext._3.AbstractAttributeFinder;


/**
 * This module implements the basic behavior of the AttributeSelectorType,
 * looking for attribute values in the physical request document using the
 * given XPath expression. This is implemented as a separate module (instead
 * of being implemented directly in <code>AttributeSelector</code> so that
 * programmers can remove this functionality if they want (it's optional in
 * the spec), so they can replace this code with more efficient, specific
 * code as needed, and so they can easily swap in different XPath libraries.
 * <p>
 * Note that if no matches are found, this module will return an empty bag
 * (unless some error occurred). The <code>AttributeSelector</code> is still
 * deciding what to return to the policy based on the MustBePresent
 * attribute.
 * <p>
 * This module uses the Xalan XPath implementation, and supports only version
 * 1.0 of XPath. It is a fully functional, correct implementation of XACML's
 * AttributeSelector functionality, but is not designed for environments
 * that make significant use of XPath queries. Developers for any such
 * environment should consider implementing their own module.
 *
 * @since 1.0
 * @author Seth Proctor
 */
public class SelectorModule extends AttributeFinderModule<AbstractAttributeFinder>
{
    private static final Logger LOGGER =
        LoggerFactory.getLogger(SelectorModule.class);
    Map<String, XPathExpression> xpathExpressionMap = new HashMap<>();

    /**
     * Returns true since this module supports retrieving attributes based on
     * the data provided in an AttributeSelectorType.
     *
     * @return true
     */
    @Override
    public boolean isSelectorSupported() {
        return true;
    }

    /**
     * Private helper to create a new processing error status result
     */
    private static EvaluationResult createProcessingError(String msg) {
        List<String> code = new ArrayList<>();
        code.add(Status.STATUS_PROCESSING_ERROR);
        return new EvaluationResult(new Status(code, msg));
    }

    /**
     * Tries to find attribute values based on the given selector data.
     * The result, if successful, always contains a <code>BagAttribute</code>,
     * even if only one value was found. If no values were found, but no other
     * error occurred, an empty bag is returned.
     *
     * @param path the XPath expression to search against
     * @param namespaceNode the DOM node defining namespace mappings to use,
     *                      or null if mappings come from the context root
     * @param type the datatype of the attributes to find
     * @param context the representation of the request data
     * @param xpathVersion the XPath version to use
     *
     * @return the result of attribute retrieval, which will be a bag of
     *         attributes or an error
     */
    @Override
	public EvaluationResult findAttribute(String path, Node namespaceNode,
                                          String type, EvaluationCtx context,
                                          String xpathVersion) {
        // we only support 1.0
        if (! xpathVersion.equals(PolicyMetaData.XPATH_1_0_IDENTIFIER))
        {
            return new EvaluationResult(BagAttribute.createEmptyBag(type));
        }

        // get the DOM root of the request document
        Node root = context.getRequestRoot();

        // if we were provided with a non-null namespace node, then use it
        // to resolve namespaces, otherwise use the context root node
        Node nsNode = (namespaceNode != null) ? namespaceNode : root;

        NamespaceContextImpl namespaceContext = new NamespaceContextImpl(nsNode);

        // setup the root path (pre-pended to the context path), which...
        String rootPath = "";

        // ...only has content if the context path is relative
        if (path.charAt(0) != '/') {
            String rootName = root.getLocalName();

            // see if the request root is in a namespace
            String namespace = root.getNamespaceURI();
            
            if (namespace == null) {
                // no namespacing, so we're done
                rootPath = "/" + rootName + "/";
            } else {
                // namespaces are used, so we need to lookup the correct
                // prefix to use in the search string
                NamedNodeMap nmap = nsNode.getAttributes();
                rootPath = null;

                for (int i = 0; i < nmap.getLength(); i++) {
                    Node n = nmap.item(i);
                    if (n.getNodeValue().equals(namespace)) {
                        // we found the matching namespace, so get the prefix
                        // and then break out
                        String name = n.getNodeName();
                        int pos = name.indexOf(':');

                        if (pos == -1) {
                            // the namespace was the default namespace
                            rootPath = "/";
                        } else {
                            // we found a prefixed namespace
                            rootPath = "/" + name.substring(pos + 1);
                        }

                        // finish off the string
                        rootPath += ":" + rootName + "/";

                        break;
                    }
                }

                // if the rootPath is still null, then we don't have any
                // definitions for the namespace
                if (rootPath == null)
                {
                    return createProcessingError("Failed to map a namespace" +
                                                 " in an XPath expression");
                }
            }
        }

        // now do the query, pre-pending the root path to the context path
        NodeList matches = null;
        try {
            XPath xpath = XPathFactory.newInstance().newXPath();
            xpath.setNamespaceContext(namespaceContext);

            /*
            XPathExpression xpathExpression = xpathExpressionMap.get(path);
            if (xpathExpression == null) {
                xpathExpression = xpath.compile(path);
                xpathExpressionMap.put(path, xpathExpression);
            }*/
            
            matches = (NodeList) xpath.evaluate(path, root, XPathConstants.NODESET);

            //Folowing code was when we used xalan as dependency for XPath
            //matches = XPathAPI.selectNodeList(root, rootPath + path, nsNode);
        } catch (Exception e) {
            LOGGER.error("Error during xpath.evaluate(...)", e);
            
            // in the case of any exception, we need to return an error
            return createProcessingError("error in XPath: " + e.getMessage());
        }

        if (matches.getLength() == 0) {
            // we didn't find anything, so we return an empty bag
            return new EvaluationResult(BagAttribute.createEmptyBag(type));
        }

        List<AttributeValue> list = new ArrayList<>();
		
		for (int i = 0; i < matches.getLength(); i++) {
		    Node node = matches.item(i);
		    final PolicyMetaData metaData = new PolicyMetaData(PolicyMetaData.XACML_3_0_IDENTIFIER, null);
		    final AttributeValue attrVal = AttributeValue.getInstance(node, metaData);
		    list.add(attrVal);
		}
		
		return new EvaluationResult(new BagAttribute(type, list));
    }

	@Override
	public void init(AbstractAttributeFinder conf)
	{
		throw new UnsupportedOperationException("Initialization method not supported. Use the default constructor instead.");
	}

	@Override
	public boolean isDesignatorSupported() {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public Set<Integer> getSupportedDesignatorTypes() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Set getSupportedIds() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public void invalidateCache() {
		// TODO Auto-generated method stub
		
	}
}
