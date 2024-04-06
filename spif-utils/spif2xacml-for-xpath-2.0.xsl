<?xml version="1.0" encoding="UTF-8"?>
<!--

    Copyright (C) 2022 THALES.

    This file is part of AuthZForce CE. Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
-->
<!-- SPIF (xmlspif.org) to XACML 3.0 Policy Conversion XSL Sheet. Author: Cyril DANGERVILLE -->
<!-- Tested with Saxon XSLT processor. -->
<xsl:stylesheet version="1.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform" xmlns:spif="http://www.xmlspif.org/spif"
                xmlns="urn:oasis:names:tc:xacml:3.0:core:schema:wd-17" xmlns:xs="http://www.w3.org/2001/XMLSchema" exclude-result-prefixes="spif xs">
    <xsl:output encoding="UTF-8" indent="yes" method="xml"/>

    <!-- This element removes indentation with Xalan 2.7.1 (indentation preserved with Saxon 9.6.0.4). -->
    <!-- <xsl:strip-space elements="*" /> -->

    <!-- Start with root element... -->
    <xsl:template match="/spif:SPIF">
        <PolicySet xmlns="urn:oasis:names:tc:xacml:3.0:core:schema:wd-17" PolicySetId="{spif:securityPolicyId/@id}"
                   Version="{@version}"
                   PolicyCombiningAlgId="urn:oasis:names:tc:xacml:3.0:policy-combining-algorithm:deny-unless-permit">
            <Description>
                <xsl:text>Generated from SPIF: </xsl:text><xsl:value-of select="spif:securityPolicyId/@name"/><xsl:text> v</xsl:text><xsl:value-of
                    select="@version"/><xsl:text>. See also NATO ADatP-4774.1 Implementation Guidance.
                   Permit iff child policy returns Permit, else Deny.
            </xsl:text>
            </Description>
            <PolicySetDefaults>
                <XPathVersion>http://www.w3.org/TR/2007/REC-xpath20-20070123</XPathVersion>
            </PolicySetDefaults>
            <Target/>

            <PolicySet PolicySetId="Permit_iff_all_children_Permit_PS" Version="1.0"
                    PolicyCombiningAlgId="urn:oasis:names:tc:xacml:3.0:policy-combining-algorithm:permit-unless-deny">
                <Description>
                    Permit if and only if all of these rules return true:
                    (Generic / SPIF-agnostic rules)
                    - Action is present with one and only one value.
                    - Idem for resource label's SPIF policy ID
                    - SPIF Policy IDs of subject clearance and resource label match.
                    (SPIF-specific rules)
                    - Resource label Policy ID matches the SPIF Policy ID.
                    - Bell-Lapadula policy satisfied: (action READ and subject classif level &gt;= resource classif level) or (action WRITE and subject level &lt;= resource level)
                    - Other confidentiality categories (besides classification) of subject clearance and resource label match.

                    Each Rule is a Permit Rule wrapped in a deny-unless-permit policy.
                    In other words, this permit-unless-deny Policy returns Permit iff no child Policy(Set) returns Deny, i.e. if all wrapped Rules return Permit.
                </Description>
                <Target />
                    <!--
      TODO: when VariableDefinitions supported in PolicySet
      <VariableDefinition VariableId="resource_label_policy_id_bag">
         <AttributeSelector Category="urn:oasis:names:tc:xacml:3.0:attribute-category:resource"
                                        Path="//*:PolicyIdentifier/text()"
                                        DataType="http://www.w3.org/2001/XMLSchema#string"
                                        MustBePresent="true"/>
      </VariableDefinition>
      -->
                <Policy PolicyId="SPIF-generic_P"
                        Version="1.0"
                        RuleCombiningAlgId="urn:oasis:names:tc:xacml:3.0:rule-combining-algorithm:deny-unless-permit">
                    <Description>
                        Generic / SPIF-agnostic rules:
                        - Action is present with one and only one value.
                        - SPIF Policy IDs of subject clearance and resource label match.
                    </Description>
                    <Target />
                    <!-- TODO: when VariableDefinitions supported in PolicySet, this would be already defined. -->
                    <VariableDefinition VariableId="resource_label_policy_id_bag">
                        <AttributeSelector Category="urn:oasis:names:tc:xacml:3.0:attribute-category:resource"
                                           Path="//*:PolicyIdentifier/text()"
                                           DataType="http://www.w3.org/2001/XMLSchema#string"
                                           MustBePresent="true"/>
                    </VariableDefinition>

                    <Rule RuleId="SPIF-generic_R" Effect="Permit">
                        <Condition>
                            <Apply FunctionId="urn:oasis:names:tc:xacml:1.0:function:and">
                                <Apply FunctionId="urn:oasis:names:tc:xacml:1.0:function:integer-equal">
                                    <Apply FunctionId="urn:oasis:names:tc:xacml:1.0:function:string-bag-size">
                                        <AttributeDesignator Category="urn:oasis:names:tc:xacml:3.0:attribute-category:action"
                                                             AttributeId="urn:oasis:names:tc:xacml:1.0:action:action-id"
                                                             DataType="http://www.w3.org/2001/XMLSchema#string"
                                                             MustBePresent="true"/>
                                    </Apply>
                                    <AttributeValue DataType="http://www.w3.org/2001/XMLSchema#integer">1</AttributeValue>
                                </Apply>
                                <Apply FunctionId="urn:oasis:names:tc:xacml:1.0:function:integer-equal">
                                    <Apply FunctionId="urn:oasis:names:tc:xacml:1.0:function:string-bag-size">
                                        <VariableReference VariableId="resource_label_policy_id_bag"/>
                                    </Apply>
                                    <AttributeValue DataType="http://www.w3.org/2001/XMLSchema#integer">1</AttributeValue>
                                </Apply>
                                <Apply FunctionId="urn:oasis:names:tc:xacml:1.0:function:string-set-equals">
                                    <VariableReference VariableId="resource_label_policy_id_bag" />
                                    <AttributeSelector
                                            Category="urn:oasis:names:tc:xacml:1.0:subject-category:access-subject"
                                            Path="//*:PolicyIdentifier/text()"
                                            DataType="http://www.w3.org/2001/XMLSchema#string" MustBePresent="true"/>
                                </Apply>
                            </Apply>
                        </Condition>
                    </Rule>
                </Policy>

                <PolicySet PolicySetId="SPIF-specific_PS"
                           Version="1.0"
                           PolicyCombiningAlgId="urn:oasis:names:tc:xacml:3.0:policy-combining-algorithm:deny-unless-permit">
                    <Description>
                        We already checked that the action is present with only one value. Same check done for the resource label's PolicyIdentifier as well.

                        SPIF-specific rules for each SPIF, i.e. if the resource label Policy ID matches the SPIF Policy ID:
                        - Check the Bell-Lapadula policy is satisfied: (action READ and subject classif level &gt;= resource classif level) or (action WRITE and subject level &lt;= resource level)
                        - And non-classif/non-hierarchical confidentiality categories of subject clearance and resource label match.
                    </Description>
                    <Target />

                    <!-- For each supported SPIF, one XACML Policy... -->
                    <Policy PolicyId="SPIF-specific_P" Version="1.0" RuleCombiningAlgId="urn:oasis:names:tc:xacml:3.0:rule-combining-algorithm:deny-unless-permit">
                        <Target>
                            <AnyOf>
                                <AllOf>
                                    <Match MatchId="urn:oasis:names:tc:xacml:1.0:function:string-equal">
                                        <AttributeValue DataType="http://www.w3.org/2001/XMLSchema#string"><xsl:value-of select="spif:securityPolicyId/@name"/></AttributeValue>
                                        <!-- TODO: use the variable in the parent PolicySet when VariableDefinitions will be supported in PolicySet -->
                                        <AttributeSelector Category="urn:oasis:names:tc:xacml:3.0:attribute-category:resource"
                                                           Path="//*:PolicyIdentifier/text()"
                                                           DataType="http://www.w3.org/2001/XMLSchema#string"
                                                           MustBePresent="true"/>
                                    </Match>
                                </AllOf>
                            </AnyOf>
                        </Target>

                        <!-- TODO: define this Variable in the parent PolicySet when VariableDefinitions will be supported in PolicySet -->
                        <VariableDefinition VariableId="action_id">
                            <Apply FunctionId="urn:oasis:names:tc:xacml:1.0:function:string-one-and-only">
                                <AttributeDesignator Category="urn:oasis:names:tc:xacml:3.0:attribute-category:action"
                                                     AttributeId="urn:oasis:names:tc:xacml:1.0:action:action-id"
                                                     DataType="http://www.w3.org/2001/XMLSchema#string"
                                                     MustBePresent="true"/>
                            </Apply>
                        </VariableDefinition>

                        <xsl:variable name="classif_name_to_level_xpath"><xsl:text>for $classif_name in //*:Classification/text() return </xsl:text><xsl:for-each select="//spif:securityClassification"><xsl:sort select="@hierarchy" order="descending" /><xsl:text>if ($classif_name = '</xsl:text><xsl:value-of select="@name" /><xsl:text>') then </xsl:text><xsl:value-of select="@hierarchy"/><xsl:text> else </xsl:text></xsl:for-each><xsl:text>0</xsl:text></xsl:variable>
                        <VariableDefinition VariableId="resource_classif_level">
                            <Apply FunctionId="urn:oasis:names:tc:xacml:1.0:function:integer-one-and-only">
                                <AttributeSelector Category="urn:oasis:names:tc:xacml:3.0:attribute-category:resource"
                                                   DataType="http://www.w3.org/2001/XMLSchema#integer"
                                                   MustBePresent="true"
                                                   Path="{$classif_name_to_level_xpath}">
                                </AttributeSelector>
                            </Apply>
                        </VariableDefinition>
                        <VariableDefinition VariableId="subject_classif_levels">
                                <AttributeSelector Category="urn:oasis:names:tc:xacml:1.0:subject-category:access-subject"
                                                   DataType="http://www.w3.org/2001/XMLSchema#integer"
                                                   MustBePresent="true"
                                                   Path="{$classif_name_to_level_xpath}">
                                </AttributeSelector>
                        </VariableDefinition>

                        <xsl:for-each select="//spif:securityCategoryTag">
                        <!-- Ignore informative categories -->
                            <xsl:if test="@tagType !='tagType7'">
                                <VariableDefinition VariableId="resource_category_values[{@name}]">
                                    <AttributeSelector Category="urn:oasis:names:tc:xacml:3.0:attribute-category:resource"
                                                       Path="//*:Category[@TagName='{@name}']/*:GenericValue/text()"
                                                       DataType="http://www.w3.org/2001/XMLSchema#string"
                                                       MustBePresent="false"/>
                                </VariableDefinition>
                            </xsl:if>
                        </xsl:for-each>

                        <Rule RuleId="SPIF-specific_R" Effect="Permit">
                            <Description>
                                - Bell-Lapadula rules:
                                  - Allow READ if and only if subject level ≥ object (resource) level
                                  - Allow WRITE if and only if subject level ≤ object (resource) level
                                - Check non-classif confidentiality categories:
                                (resource label has no C1 value OR subject clearance/resource label C1 values match) AND (resource label has no C2 value OR subject clearance/resource label C2 values match) AND ...

                                The 'has no value' is translated to: the AttributeSelector returns an empty bag (size 0).
                                The 'match' function depends on whether the category is permissive (at least one value must match, i.e. 'at-least-one-member-of' function) or restrictive (all must match, i.e. 'subset' function).
                            </Description>
                            <Condition>
                                <Apply FunctionId="urn:oasis:names:tc:xacml:1.0:function:and">
                                    <Apply FunctionId="urn:oasis:names:tc:xacml:1.0:function:or">
                                        <Apply FunctionId="urn:oasis:names:tc:xacml:1.0:function:and">
                                            <Apply FunctionId="urn:oasis:names:tc:xacml:1.0:function:string-equal">
                                                <VariableReference VariableId="action_id"/>
                                                <AttributeValue DataType="http://www.w3.org/2001/XMLSchema#string">READ</AttributeValue>
                                            </Apply>
                                            <Apply FunctionId="urn:oasis:names:tc:xacml:3.0:function:any-of">
                                                <Function FunctionId="urn:oasis:names:tc:xacml:1.0:function:integer-less-than-or-equal" />
                                                <VariableReference VariableId="resource_classif_level"/>
                                                <VariableReference VariableId="subject_classif_levels"/>
                                            </Apply>
                                        </Apply>
                                        <Apply FunctionId="urn:oasis:names:tc:xacml:1.0:function:and">
                                            <Apply FunctionId="urn:oasis:names:tc:xacml:1.0:function:string-equal">
                                                <VariableReference VariableId="action_id"/>
                                                <AttributeValue DataType="http://www.w3.org/2001/XMLSchema#string">WRITE</AttributeValue>
                                            </Apply>
                                            <Apply FunctionId="urn:oasis:names:tc:xacml:3.0:function:any-of">
                                                <Function FunctionId="urn:oasis:names:tc:xacml:1.0:function:integer-greater-than-or-equal" />
                                                <VariableReference VariableId="resource_classif_level"/>
                                                <VariableReference VariableId="subject_classif_levels"/>
                                            </Apply>
                                        </Apply>
                                    </Apply>

                                    <!-- For each non-classif/non-hierarchical category,
                                    if the category is present, match the subject/resource values against each other.
                                    -->
                                    <xsl:for-each select="//spif:securityCategoryTag">
                                        <!-- Ignore informative categories -->
                                        <xsl:if test="@tagType !='tagType7'">
                                            <Apply FunctionId="urn:oasis:names:tc:xacml:1.0:function:or">
                                                <Apply FunctionId="urn:oasis:names:tc:xacml:1.0:function:integer-equal">
                                                    <Apply FunctionId="urn:oasis:names:tc:xacml:1.0:function:string-bag-size">
                                                        <VariableReference VariableId="resource_category_values[{@name}]" />
                                                    </Apply>
                                                    <AttributeValue DataType="http://www.w3.org/2001/XMLSchema#integer">0</AttributeValue>
                                                </Apply>
                                                <!-- The comparison function depends on whether it is a permissive (at least one from the label must match one in the subject clearance) or restrictive (all from the label must match one in the subject clearance) category. -->
                                                <xsl:variable name="comparFuncId"><xsl:choose><xsl:when test="@tagType='permissive' or @enumType='permissive'">urn:oasis:names:tc:xacml:1.0:function:string-at-least-one-member-of</xsl:when><xsl:otherwise>urn:oasis:names:tc:xacml:1.0:function:string-subset</xsl:otherwise></xsl:choose></xsl:variable>
                                                <Apply FunctionId="{$comparFuncId}">
                                                    <VariableReference VariableId="resource_category_values[{@name}]" />
                                                    <AttributeSelector Category="urn:oasis:names:tc:xacml:1.0:subject-category:access-subject"
                                                                       Path="//*:Category[@TagName='{@name}']/*:GenericValue/text()"
                                                                       DataType="http://www.w3.org/2001/XMLSchema#string"
                                                                       MustBePresent="false"/>
                                                </Apply>
                                            </Apply>
                                        </xsl:if>
                                    </xsl:for-each>
                                </Apply>
                            </Condition>
                        </Rule>
                    </Policy>

                    <!-- Other SPIF-specific PolicySet if any
                     <PolicySet>...</PolicySet>
                     -->
                </PolicySet>
            </PolicySet>
        </PolicySet>
    </xsl:template>

</xsl:stylesheet>