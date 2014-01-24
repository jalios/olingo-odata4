/*******************************************************************************
 * 
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 ******************************************************************************/

package org.apache.olingo.odata4.producer.core.uri;

import java.util.ArrayList;
import java.util.List;
import java.util.Stack;

import org.antlr.v4.runtime.misc.ParseCancellationException;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNodeImpl;
import org.apache.olingo.odata4.commons.api.edm.Edm;
import org.apache.olingo.odata4.commons.api.edm.EdmAction;
import org.apache.olingo.odata4.commons.api.edm.EdmActionImport;
import org.apache.olingo.odata4.commons.api.edm.EdmComplexType;
import org.apache.olingo.odata4.commons.api.edm.EdmElement;
import org.apache.olingo.odata4.commons.api.edm.EdmEntityContainer;
import org.apache.olingo.odata4.commons.api.edm.EdmEntitySet;
import org.apache.olingo.odata4.commons.api.edm.EdmEntityType;
import org.apache.olingo.odata4.commons.api.edm.EdmFunction;
import org.apache.olingo.odata4.commons.api.edm.EdmFunctionImport;
import org.apache.olingo.odata4.commons.api.edm.EdmNavigationProperty;
import org.apache.olingo.odata4.commons.api.edm.EdmProperty;
import org.apache.olingo.odata4.commons.api.edm.EdmSingleton;
import org.apache.olingo.odata4.commons.api.edm.EdmStructuralType;
import org.apache.olingo.odata4.commons.api.edm.EdmType;
import org.apache.olingo.odata4.commons.api.edm.provider.FullQualifiedName;
import org.apache.olingo.odata4.producer.api.uri.UriInfoKind;
import org.apache.olingo.odata4.producer.api.uri.UriResourcePart;
import org.apache.olingo.odata4.producer.api.uri.UriResourcePartTyped;
import org.apache.olingo.odata4.producer.api.uri.queryoption.SelectItem;
import org.apache.olingo.odata4.producer.api.uri.queryoption.expression.SupportedBinaryOperators;
import org.apache.olingo.odata4.producer.api.uri.queryoption.expression.SupportedMethodCalls;
import org.apache.olingo.odata4.producer.core.uri.antlr.UriLexer;
import org.apache.olingo.odata4.producer.core.uri.antlr.UriParserBaseVisitor;
import org.apache.olingo.odata4.producer.core.uri.antlr.UriParserParser.SkipContext;
import org.apache.olingo.odata4.producer.core.uri.antlr.UriParserParser.SkiptokenContext;
import org.apache.olingo.odata4.producer.core.uri.antlr.UriParserParser.TopContext;
import org.apache.olingo.odata4.producer.core.uri.antlr.UriParserParser.*;
import org.apache.olingo.odata4.producer.core.uri.queryoption.*;
import org.apache.olingo.odata4.producer.core.uri.queryoption.expression.*;

/**
 * UriVisitor
 * 
 * Converts the URI parse tree from the generate URI parser into an internal representation
 * which maybe (TODO) given to the application.
 * While converting the tree is validated against the EDM.
 * 
 * Attention:
 * <li> This UriVisitor is at some point more lax than the original ABNF:
 * <li> It is more tolarable against additional whitespaces
 * - Whenever it is possible to move edm validation to the AST classes then
 * this sould be done ( see visit {@link #visitSelectSegment} for example)
 * 
 * Not supported (TODO)
 * - Parsing the context of $metadata
 */
public class UriParseTreeVisitor extends UriParserBaseVisitor<Object> {
  private Edm edm;

  private EdmEntityContainer edmEntityContainer;

  /**
   * Hold all currently allowed lambda variables
   * As lambda functions can be nested there may be more than one allowed lambda variables while parsing a
   * $filter or $orderby expression.
   */
  private class LambdaVariables {
    public boolean isCollection;
    public String name;
    public EdmType type;
  }

  /**
   * Set to allow nodes deeper in the parse tree appending path segments to the currently
   * processed {@link ExpandItemImpl}.
   * <li>The context is required because the following path segments depend on the successor.</li>
   * <li>A stack is used because some system query options which are applied to the last type can
   * be nested ($expand, $filter)</li>
   */
  private class LastTypeColl {
    private boolean isCollection;
    private EdmType type;

    LastTypeColl(EdmType type, boolean isCollection) {
      this.type = type;
      this.isCollection = isCollection;
    }
  }

  // --- context ---

  private Stack<LambdaVariables> allowedLambdaVariables = new Stack<LambdaVariables>();

  /**
   * Set within method {@link #visitExpandItem(ExpandPathContext ctx)} and {@link #visitExpandPathExtension(final
   * ExpandPathExtensionContext ctx)} to allow nodes
   * deeper in the expand tree at {@link #visitExpandPathExtension(ExpandPathExtensionContext ctx)} appending path
   * segments to the currently processed {@link ExpandItemImpl}.
   */
  private ExpandItemImpl contextExpandItemPath;

  /**
   * Set within method {@link #visitSelectItem(SelectItemContext ctx)} to allow nodes
   * deeper in the expand tree at {@link #visitSelectSegment(SelectSegmentContext ctx)} appending path segments to the
   * currently processed {@link SelectItemOptionImpl}.
   */
  private SelectItemOptionImpl contextSelectItem;

  private Stack<LastTypeColl> contextTypes = new Stack<LastTypeColl>();

  /**
   * Stores the currently processed UriInfo objects. There is one URI Info object for the resource path
   * and one for each new first member access within $filter and $orderBy options.
   */
  private UriInfoImpl contextUriInfo;

  // --- class ---

  public UriParseTreeVisitor(final Edm edm) {
    this.edm = edm;
    edmEntityContainer = edm.getEntityContainer(null);
  }

  private FullQualifiedName getFullName(final NamespaceContext vNS, final String odi) {
    if (vNS != null) {
      String namespace = vNS.getText();
      namespace = namespace.substring(0, namespace.length() - 1);

      return new FullQualifiedName(namespace, odi);
    }
    return null;

  }

  /* here */
  private LambdaVariables getLambdaVar(String odi) {
    for (LambdaVariables item : this.allowedLambdaVariables) {
      if (item.name.equals(odi)) {
        return item;
      }
    }
    return null;
  }

  private EdmType getLastType(final UriResourceImplTyped lastResourceSegment) {
    if (lastResourceSegment instanceof UriResourceImplKeyPred) {
      UriResourceImplKeyPred lastKeyPred = (UriResourceImplKeyPred) lastResourceSegment;
      if (lastKeyPred.getTypeFilterOnEntry() != null) {
        return lastKeyPred.getTypeFilterOnEntry();
      } else if (lastKeyPred.getTypeFilterOnCollection() != null) {
        return lastKeyPred.getTypeFilterOnCollection();
      }
    }
    EdmType type = lastResourceSegment.getTypeFilter();
    if (type != null) {
      return type;
    }

    return lastResourceSegment.getType();
  }

  public UriResourceImplTyped readFirstPathInfoSegment(final PathSegmentContext ctx) {
    String odi = ctx.vODI.getText();

    // check EntitySet
    EdmEntitySet edmEntitySet = edmEntityContainer.getEntitySet(odi);
    if (edmEntitySet != null) {
      UriResourceEntitySetImpl uriResource = new UriResourceEntitySetImpl();
      uriResource.setEntitSet(edmEntitySet);
      contextUriInfo.addPathInfo(uriResource);
      return null;
    }

    // check Singleton
    EdmSingleton edmSingleton = edmEntityContainer.getSingleton(odi);
    if (edmSingleton != null) {
      UriResourceSingletonImpl uriResource = new UriResourceSingletonImpl();
      uriResource.setSingleton(edmSingleton);
      contextUriInfo.addPathInfo(uriResource);
      return null;
    }

    // check ActionImport
    EdmActionImport edmActionImport = edmEntityContainer.getActionImport(odi);
    if (edmActionImport != null) {
      UriResourceActionImpl uriResource = new UriResourceActionImpl();
      uriResource.setActionImport(edmActionImport);
      contextUriInfo.addPathInfo(uriResource);
      return null;
    }

    // check FunctionImport
    EdmFunctionImport edmFunctionImport = edmEntityContainer.getFunctionImport(odi);
    if (edmFunctionImport != null) {

      // read the URI parameters
      List<UriParameterImpl> parameters = (List<UriParameterImpl>) ctx.vlNVO.get(0).accept(this);
      ctx.vlNVO.remove(0); // parameters are consumed

      UriResourceFunctionImpl uriResource = new UriResourceFunctionImpl();
      uriResource.setFunctionImport(edmFunctionImport, parameters);
      contextUriInfo.addPathInfo(uriResource);
      return null;
    }

    throw wrap(new UriParserSemanticException("Unkown path segment: " + odi));
  }

  public UriResourceImplTyped readNextPathInfoSegment(final PathSegmentContext ctx) {
    String odi = ctx.vODI.getText();

    UriResourcePart lastResource = contextUriInfo.getLastResourcePart();
    if (!(lastResource instanceof UriResourceImplTyped)) {
      throw wrap(new UriParserSemanticException("Previous path segment not typed"));
    }

    UriResourceImplTyped lastTyped = (UriResourceImplTyped) lastResource;

    if (ctx.vNS == null) { // without namespace

      // first check for lambda variable because a newly add property should not shadow a long used lambda variable
      LambdaVariables lVar = getLambdaVar(odi);
      if (lVar != null) {
        UriResourceLambdaRefImpl lambdaResource = new UriResourceLambdaRefImpl();
        lambdaResource.setVariableText(lVar.name);
        lambdaResource.setType(lVar.type);
        lambdaResource.isCollection(lVar.isCollection);
        contextUriInfo.addPathInfo(lambdaResource);
        return null;
      }

      EdmType targetType = getLastType(lastTyped);
      if (!(targetType instanceof EdmStructuralType)) {
        throw wrap(new UriParserSemanticException("Previous path segment not a structural type"));
      }

      EdmStructuralType structType = (EdmStructuralType) targetType;

      EdmElement property = structType.getProperty(odi);
      if (property == null) {
        throw wrap(new UriParserSemanticException("Unkown property: " + odi));
      }

      if (property instanceof EdmProperty) {
        if (((EdmProperty) property).isPrimitive() == true) {
          // create simple property
          UriResourceSimplePropertyImpl simpleResource = new UriResourceSimplePropertyImpl();
          simpleResource.setProperty((EdmProperty) property);
          contextUriInfo.addPathInfo(simpleResource);
          return null;
        } else {
          // create complex property
          UriResourceComplexPropertyImpl complexResource = new UriResourceComplexPropertyImpl();
          complexResource.setProperty((EdmProperty) property);
          contextUriInfo.addPathInfo(complexResource);
          return null;
        }
      } else if (property instanceof EdmNavigationProperty) {
        // create navigation property
        UriResourceNavigationPropertyImpl navigationResource = new UriResourceNavigationPropertyImpl();
        navigationResource.setNavigationProperty((EdmNavigationProperty) property);
        contextUriInfo.addPathInfo(navigationResource);
        return null;
      } else {
        throw wrap(new UriParserSemanticException("Unkown property type"));
      }

    } else { // with namespace

      FullQualifiedName fullFilterName = getFullName(ctx.vNS, odi);

      EdmType lastType = getLastType(lastTyped);

      if (lastType instanceof EdmEntityType) {

        EdmEntityType filterEntityType = edm.getEntityType(fullFilterName);
        if (filterEntityType != null) {

          // is entity type cast
          if (!(filterEntityType.compatibleTo(lastType))) {
            throw wrap(new UriParserSemanticException(
                "Entity typefilter not compatible to previous path segment: " + fullFilterName.toString()));
          }

          // check if last segment may contain key properties
          if (lastTyped instanceof UriResourceImplKeyPred) {
            UriResourceImplKeyPred lastKeyPred = (UriResourceImplKeyPred) lastTyped;

            if (lastKeyPred.isCollection() == false) {
              if (lastKeyPred.getTypeFilterOnEntry() != null) {
                throw wrap(new UriParserSemanticException("Single type filters are not chainable"));
              }
              lastKeyPred.setSingleTypeFilter(filterEntityType);
              return null;
            } else {
              if (lastKeyPred.getTypeFilterOnCollection() != null) {
                throw wrap(new UriParserSemanticException("Collection type filters are not chainable"));
              }
              lastKeyPred.setCollectionTypeFilter(filterEntityType);
              return null;
            }
          } else {
            if (lastTyped.getTypeFilter() != null) {
              throw wrap(new UriParserSemanticException("type filters are not chainable"));
            }

            lastTyped.setTypeFilter(filterEntityType);
            return null;
          }
        }

      } else if (lastType instanceof EdmComplexType) {

        EdmComplexType filterComplexType = edm.getComplexType(fullFilterName);

        if (filterComplexType != null) {

          // is complex type cast
          if (!(filterComplexType.compatibleTo(lastType))) {
            throw wrap(new UriParserSemanticException(
                "Complex typefilter not compatible to previous path segment: " + fullFilterName.toString()));
          }

          // is simple complex type cast
          if (lastTyped instanceof UriResourceImplKeyPred) {
            // e.g. in case of function returning complex data or a list of complex data
            UriResourceImplKeyPred lastKeyPred = (UriResourceImplKeyPred) lastTyped;

            if (lastKeyPred.isCollection() == false) {
              if (lastKeyPred.getTypeFilterOnEntry() != null) {
                throw wrap(new UriParserSemanticException("Single TypeFilter are not chainable"));
              }
              lastKeyPred.setSingleTypeFilter(filterComplexType);
              return null;
            } else {
              if (lastKeyPred.getTypeFilterOnCollection() != null) {
                throw wrap(new UriParserSemanticException("Collection TypeFilter are not chainable"));
              }
              lastKeyPred.setCollectionTypeFilter(filterComplexType);
              return null;
            }

          } else {
            if (lastTyped.getTypeFilter() != null) {
              throw wrap(new UriParserSemanticException("Chaining Typefilters not allowed"));
            }

            lastTyped.setTypeFilter(filterComplexType);
            return null;
          }
        }
      }

      FullQualifiedName fullBindingTypeName = new FullQualifiedName(lastType.getNamespace(), lastType.getName());

      // check for action
      EdmAction action = edm.getAction(fullFilterName, fullBindingTypeName, lastTyped.isCollection());
      if (action != null) {
        UriResourceActionImpl pathInfoAction = new UriResourceActionImpl();
        pathInfoAction.setAction(action);
        contextUriInfo.addPathInfo(pathInfoAction);
        return null;
      }

      // do a check for bound functions (which requires the parameter list)

      if (ctx.vlNVO.size() == 0) {
        throw wrap(new UriParserSemanticException("Expected function parameters"));
      }

      List<UriParameterImpl> parameters = (List<UriParameterImpl>) ctx.vlNVO.get(0).accept(this);

      // get names of function parameters
      List<String> names = new ArrayList<String>();
      for (UriParameterImpl item : parameters) {
        names.add(item.getName());
      }

      EdmFunction function = edm.getFunction(fullFilterName, fullBindingTypeName, lastTyped.isCollection(), names);

      if (function != null) {
        UriResourceFunctionImpl pathInfoFunction = new UriResourceFunctionImpl();
        pathInfoFunction.setFunction(function);
        pathInfoFunction.setParameters(parameters);
        contextUriInfo.addPathInfo(pathInfoFunction);
        ctx.vlNVO.remove(0);// parameters are consumed
        return null;
      }

      // check for unbound function in the $filter case ( where the previous resource segment is a $it)
      if (lastTyped instanceof UriResourceItImpl) {
        if (((UriResourceItImpl) lastTyped).isExplicitIt() == false) {
          // check for unbound function
          EdmFunction functionUnbound = edm.getFunction(fullFilterName, null, lastTyped.isCollection(), names);
          if (functionUnbound != null) {
            contextUriInfo.clearPathInfo(); // replace the $it

            // TODO maybe add $root pathinfo first
            UriResourceFunctionImpl pathInfoFunction = new UriResourceFunctionImpl();
            pathInfoFunction.setFunction(functionUnbound);
            pathInfoFunction.setParameters(parameters);
            contextUriInfo.addPathInfo(pathInfoFunction);
            ctx.vlNVO.remove(0);// parameters are consumed
            return null;
          }
        }
      }
      throw wrap(new UriParserSemanticException("Unknown resource path segment:" + fullFilterName.toString()));

    }
  }

  @Override
  public Object visitAliasAndValue(final AliasAndValueContext ctx) {
    AliasQueryOptionImpl alias = new AliasQueryOptionImpl();
    alias.setName(ctx.vODI.getText());
    alias.setText(ctx.vV.getText());
    alias.setAliasValue((ExpressionImpl) ctx.vV.accept(this));
    return alias;
  }

  @Override
  public Object visitAllExpr(final AllExprContext ctx) {
    UriResourceAllImpl all = new UriResourceAllImpl();

    LastTypeColl lastTypeInfo = this.contextTypes.peek();

    LambdaVariables var = new LambdaVariables();
    var.name = ctx.vLV.getText();
    var.type = lastTypeInfo.type;
    var.isCollection = lastTypeInfo.isCollection;

    all.setLamdaVariable(ctx.vLV.getText());
    allowedLambdaVariables.push(var);
    all.setExpression((ExpressionImpl) ctx.vLE.accept(this));
    allowedLambdaVariables.pop();
    return all;
  }

  @Override
  public ExpressionImpl visitAltAdd(final AltAddContext ctx) {
    BinaryImpl binary = new BinaryImpl();

    int tokenIndex = ctx.vO.getType();

    if (tokenIndex == UriLexer.ADD) {
      binary.setOperator(SupportedBinaryOperators.ADD);
    } else if (tokenIndex == UriLexer.SUB) {
      binary.setOperator(SupportedBinaryOperators.SUB);
    }

    binary.setLeftOperand((ExpressionImpl) ctx.vE1.accept(this));
    binary.setRightOperand((ExpressionImpl) ctx.vE2.accept(this));
    return binary;
  }

  @Override
  public Object visitAltAll(final AltAllContext ctx) {
    UriInfoImpl uriInfoImplpath = new UriInfoImpl().setKind(UriInfoKind.resource);

    UriResourceItImpl pathInfoIT = new UriResourceItImpl();
    LastTypeColl lastTypeInfo = this.contextTypes.peek();
    pathInfoIT.setType(lastTypeInfo.type);
    pathInfoIT.setCollection(lastTypeInfo.isCollection);

    uriInfoImplpath.addPathInfo(pathInfoIT);
    uriInfoImplpath.addPathInfo((UriResourcePartImpl) super.visitAltAll(ctx));

    return new MemberImpl()
        .setPath(uriInfoImplpath);
  }

  @Override
  public ExpressionImpl visitAltAnd(final AltAndContext ctx) {
    BinaryImpl binary = new BinaryImpl();

    binary.setOperator(SupportedBinaryOperators.AND);
    binary.setLeftOperand((ExpressionImpl) ctx.vE1.accept(this));
    binary.setRightOperand((ExpressionImpl) ctx.vE2.accept(this));
    return binary;
  }

  @Override
  public Object visitAltAny(final AltAnyContext ctx) {
    UriInfoImpl uriInfoImplpath = new UriInfoImpl().setKind(UriInfoKind.resource);

    UriResourceItImpl pathInfoIT = new UriResourceItImpl();
    pathInfoIT.setType(contextTypes.peek().type);
    pathInfoIT.setCollection(contextTypes.peek().isCollection);

    uriInfoImplpath.addPathInfo(pathInfoIT);
    uriInfoImplpath.addPathInfo((UriResourcePartImpl) super.visitAltAny(ctx));

    return new MemberImpl()
        .setPath(uriInfoImplpath);
  }

  @Override
  public Object visitAltBatch(final AltBatchContext ctx) {
    contextUriInfo = new UriInfoImpl().setKind(UriInfoKind.batch);
    return null;
  }

  @Override
  public ExpressionImpl visitAltComparism(final AltComparismContext ctx) {
    BinaryImpl binary = new BinaryImpl();

    int tokenIndex = ctx.vO.getType();

    if (tokenIndex == UriLexer.GT) {
      binary.setOperator(SupportedBinaryOperators.GT);
    } else if (tokenIndex == UriLexer.GE) {
      binary.setOperator(SupportedBinaryOperators.GE);
    } else if (tokenIndex == UriLexer.LT) {
      binary.setOperator(SupportedBinaryOperators.LT);
    } else if (tokenIndex == UriLexer.LE) {
      binary.setOperator(SupportedBinaryOperators.LE);
    } else if (tokenIndex == UriLexer.ISOF) {
      binary.setOperator(SupportedBinaryOperators.ISOF);
    }

    binary.setLeftOperand((ExpressionImpl) ctx.vE1.accept(this));
    binary.setRightOperand((ExpressionImpl) ctx.vE2.accept(this));
    return binary;
  }

  @Override
  public Object visitAltEntity(final AltEntityContext ctx) {
    UriInfoImpl uriInfo = new UriInfoImpl().setKind(UriInfoKind.entityId);

    List<QueryOptionImpl> list = (List<QueryOptionImpl>) ctx.vEO.accept(this);
    uriInfo.setQueryOptions(list);

    contextUriInfo = uriInfo;
    return null;
  }

  @Override
  public Object visitAltEntityCast(final AltEntityCastContext ctx) {
    UriInfoImpl uriInfo = new UriInfoImpl().setKind(UriInfoKind.entityId);

    String odi = ctx.vODI.getText();
    FullQualifiedName fullName = getFullName(ctx.vNS, odi);

    EdmEntityType type = edm.getEntityType(fullName);
    if (type == null) {
      throw wrap(new UriParserSemanticException("Expected EntityTypeName"));
    }
    uriInfo.setEntityTypeCast(type);

    contextUriInfo = uriInfo;
    contextTypes.push(new LastTypeColl(uriInfo.getEntityTypeCast(), true));

    List<QueryOptionImpl> list = (List<QueryOptionImpl>) ctx.vEO.accept(this);
    uriInfo.setQueryOptions(list);

    return null;
  }

  @Override
  public ExpressionImpl visitAltEquality(final AltEqualityContext ctx) {
    BinaryImpl binary = new BinaryImpl();

    int tokenIndex = ctx.vO.getType();

    if (tokenIndex == UriLexer.EQ_ALPHA) {
      binary.setOperator(SupportedBinaryOperators.EQ);
    } else if (tokenIndex == UriLexer.NE) {
      binary.setOperator(SupportedBinaryOperators.NE);
    }

    binary.setLeftOperand((ExpressionImpl) ctx.vE1.accept(this));
    binary.setRightOperand((ExpressionImpl) ctx.vE2.accept(this));
    return binary;
  }

  @Override
  public Object visitAltLiteral(final AltLiteralContext ctx) {
    return new LiteralImpl().setText(ctx.getText());
  }

  @Override
  public Object visitAltMetadata(final AltMetadataContext ctx) {
    UriInfoImpl uriInfo = new UriInfoImpl().setKind(UriInfoKind.metadata);

    if (ctx.vF != null) {
      FormatOptionImpl format = (FormatOptionImpl) ctx.vF.accept(this);
      uriInfo.setFormat(format);
    }

    if (ctx.vCF != null) {
      throw wrap(new UriParserException("Fragment for $metadata not supported"));
    }

    contextUriInfo = uriInfo;
    return null;
  }

  @Override
  public ExpressionImpl visitAltMult(final AltMultContext ctx) {
    BinaryImpl binary = new BinaryImpl();

    int tokenIndex = ctx.vO.getType();

    if (tokenIndex == UriLexer.MUL) {
      binary.setOperator(SupportedBinaryOperators.MUL);
    } else if (tokenIndex == UriLexer.DIV) {
      binary.setOperator(SupportedBinaryOperators.DIV);
    } else if (tokenIndex == UriLexer.MOD) {
      binary.setOperator(SupportedBinaryOperators.MOD);
    }

    binary.setLeftOperand((ExpressionImpl) ctx.vE1.accept(this));
    binary.setRightOperand((ExpressionImpl) ctx.vE2.accept(this));
    return binary;
  }

  @Override
  public ExpressionImpl visitAltOr(final AltOrContext ctx) {
    BinaryImpl binary = new BinaryImpl();

    binary.setOperator(SupportedBinaryOperators.OR);

    binary.setLeftOperand((ExpressionImpl) ctx.vE1.accept(this));
    binary.setRightOperand((ExpressionImpl) ctx.vE2.accept(this));
    return binary;
  }

  @Override
  public Object visitAltResourcePath(final AltResourcePathContext ctx) {
    ctx.vRP.accept(this);

    if (ctx.vQO != null) {
      UriResourcePart lastSegment = contextUriInfo.getLastResourcePart();

      if (lastSegment instanceof UriResourceImplTyped) {
        UriResourceImplTyped typed = (UriResourceImplTyped) lastSegment;
        contextTypes.push(new LastTypeColl(getLastType(typed), typed.isCollection()));
      }
      contextUriInfo.setQueryOptions((List<QueryOptionImpl>) ctx.vQO.accept(this));
    }
    return null;
  }

  @Override
  public Object visitAnyExpr(final AnyExprContext ctx) {
    UriResourceAnyImpl any = new UriResourceAnyImpl();
    if (ctx.vLV != null) {
      LastTypeColl lastTypeInfo = this.contextTypes.peek();

      LambdaVariables var = new LambdaVariables();
      var.name = ctx.vLV.getText();
      var.type = lastTypeInfo.type;
      var.isCollection = lastTypeInfo.isCollection;

      any.setLamdaVariable(ctx.vLV.getText());
      allowedLambdaVariables.push(var);
      any.setExpression((ExpressionImpl) ctx.vLE.accept(this));
      allowedLambdaVariables.pop();
    }
    return any;
  }

  @Override
  public ExpressionImpl visitCastExpr(final CastExprContext ctx) {
    MethodCallImpl method = new MethodCallImpl();
    if (ctx.vE1 != null) {
      // is optional parameter
      ExpressionImpl onExpression = (ExpressionImpl) ctx.vE1.accept(this);
      method.addParameter(onExpression);
    }

    FullQualifiedName fullName = new FullQualifiedName(ctx.vNS.getText(), ctx.vODI.getText());
    EdmType type = edm.getTypeDefinition(fullName);
    method.setMethod(SupportedMethodCalls.CAST);
    method.addParameter(new TypeLiteralImpl().setType(type));

    return method;
  }

  @Override
  public ExpressionImpl visitCeilingMethodCallExpr(final CeilingMethodCallExprContext ctx) {
    return new MethodCallImpl()
        .setMethod(SupportedMethodCalls.CEILING)
        .addParameter((ExpressionImpl) ctx.vE1.accept(this));
  }

  @Override
  public ExpressionImpl visitConcatMethodCallExpr(final ConcatMethodCallExprContext ctx) {
    return new MethodCallImpl()
        .setMethod(SupportedMethodCalls.CONCAT)
        .addParameter((ExpressionImpl) ctx.vE1.accept(this))
        .addParameter((ExpressionImpl) ctx.vE2.accept(this));
  }

  @Override
  public Object visitConstSegment(final ConstSegmentContext ctx) {
    UriInfoImpl uriInfoResource = contextUriInfo;
    UriResourcePart pathInfo = uriInfoResource.getLastResourcePart();

    if (ctx.vV != null) {
      if (pathInfo instanceof UriResourcePartTyped) {
        if (!((UriResourcePartTyped) pathInfo).isCollection()) {
          contextUriInfo.addPathInfo(new UriResourceValueImpl());
        } else {
          throw wrap(new UriParserSemanticException("$value only allowed on typed path segments"));
        }
        return null;
      } else {
        throw wrap(new UriParserSemanticException("$value only allowed on typed path segments"));
      }

    } else if (ctx.vC != null) {
      if (pathInfo instanceof UriResourceImplTyped) {
        if (((UriResourceImplTyped) pathInfo).isCollection()) {
          contextUriInfo.addPathInfo(new UriResourceCountImpl());
        } else {
          throw wrap(new UriParserSemanticException("$count only allowed on collection properties"));
        }
      } else {
        throw wrap(new UriParserSemanticException("$count only allowed on typed properties"));
      }
    } else if (ctx.vR != null) {
      if (pathInfo instanceof UriResourceImplTyped) {
        EdmType type = ((UriResourceImplTyped) pathInfo).getType();
        if (type instanceof EdmEntityType) {
          contextUriInfo.addPathInfo(new UriResourceRefImpl());
        } else {
          throw wrap(new UriParserSemanticException("$ref only allowd on endity types"));
        }
      } else {
        throw wrap(new UriParserSemanticException("$ref only allowed on typed properties"));
      }

    } else if (ctx.vAll != null) {
      contextUriInfo.addPathInfo((UriResourceAllImpl) ctx.vAll.accept(this));
    } else if (ctx.vAny != null) {
      contextUriInfo.addPathInfo((UriResourceAnyImpl) ctx.vAny.accept(this));
    }
    return null;
  }

  @Override
  public ExpressionImpl visitContainsMethodCallExpr(final ContainsMethodCallExprContext ctx) {
    return new MethodCallImpl()
        .setMethod(SupportedMethodCalls.CONTAINS)
        .addParameter((ExpressionImpl) ctx.vE1.accept(this))
        .addParameter((ExpressionImpl) ctx.vE2.accept(this));
  }

  @Override
  public Object visitCrossjoin(final CrossjoinContext ctx) {
    UriInfoImpl crossJoin = new UriInfoImpl().setKind(UriInfoKind.crossjoin);

    for (OdataIdentifierContext obj : ctx.vlODI) {
      crossJoin.addEntitySetName(obj.getText());
    }

    contextUriInfo = crossJoin;
    return null;
  }

  @Override
  public Object visitCustomQueryOption(final CustomQueryOptionContext ctx) {
    CustomQueryOptionImpl queryOption = new CustomQueryOptionImpl();
    queryOption.setName(ctx.getChild(0).getText());

    // set value only if present
    if (ctx.getChildCount() > 1) {
      queryOption.setText(ctx.getChild(2).getText());
    }

    return queryOption;
  }

  @Override
  public ExpressionImpl visitDayMethodCallExpr(final DayMethodCallExprContext ctx) {
    return new MethodCallImpl()
        .setMethod(SupportedMethodCalls.DAY)
        .addParameter((ExpressionImpl) ctx.vE1.accept(this));
  }

  @Override
  public ExpressionImpl visitDistanceMethodCallExpr(final DistanceMethodCallExprContext ctx) {
    return new MethodCallImpl()
        .setMethod(SupportedMethodCalls.DISTANCE)
        .addParameter((ExpressionImpl) ctx.vE1.accept(this))
        .addParameter((ExpressionImpl) ctx.vE2.accept(this));
  }

  @Override
  public Object visitEndsWithMethodCallExpr(final EndsWithMethodCallExprContext ctx) {
    return new MethodCallImpl()
        .setMethod(SupportedMethodCalls.ENDSWITH)
        .addParameter((ExpressionImpl) ctx.vE1.accept(this));
  }

  @Override
  public Object visitEntityOptions(final EntityOptionsContext ctx) {
    List<QueryOptionImpl> queryOptionList = new ArrayList<QueryOptionImpl>();

    for (EntityOptionContext entityOption : ctx.vlEOb) {
      queryOptionList.add((QueryOptionImpl) entityOption.accept(this));
    }

    queryOptionList.add((QueryOptionImpl) ctx.vlEOm.accept(this));

    for (EntityOptionContext entityOption : ctx.vlEOa) {
      queryOptionList.add((QueryOptionImpl) entityOption.accept(this));
    }
    return queryOptionList;
  }

  @Override
  public Object visitEntityOptionsCast(final EntityOptionsCastContext ctx) {
    List<QueryOptionImpl> queryOptionList = new ArrayList<QueryOptionImpl>();
    for (EntityOptionCastContext entityOption : ctx.vlEOb) {
      queryOptionList.add((QueryOptionImpl) entityOption.accept(this));
    }

    queryOptionList.add((QueryOptionImpl) ctx.vlEOm.accept(this));

    for (EntityOptionCastContext entityOption : ctx.vlEOa) {
      queryOptionList.add((QueryOptionImpl) entityOption.accept(this));
    }
    return queryOptionList;
  }

  @Override
  public Object visitExpand(final ExpandContext ctx) {
    ExpandOptionImpl expand = new ExpandOptionImpl();
    for (ExpandItemContext eI : ctx.vlEI) {
      expand.addExpandItem((ExpandItemImpl) eI.accept(this));
    }

    return expand;
  }

  @Override
  public Object visitExpandItem(final ExpandItemContext ctx) {

    ExpandItemImpl expandItem = null;
    if (ctx.vS != null) {
      expandItem = new ExpandItemImpl().setIsStar(true);
      if (ctx.vR != null) {
        expandItem.setIsRef(true);
      } else if (ctx.vM != null) {
        expandItem.setExpandQueryOption(new LevelExpandOptionImpl().setMax().setText(ctx.vM.getText()));
      } else if (ctx.vL != null) {
        expandItem.setExpandQueryOption(new LevelExpandOptionImpl().setText(ctx.vL.getText()));
      }

    } else if (ctx.vEP != null) {
      expandItem = (ExpandItemImpl) ctx.vEP.accept(this);

      if (ctx.vEPE != null) {
        ExpandItemImpl contextExpandItemPathBU = contextExpandItemPath;
        contextExpandItemPath = expandItem;

        List<SystemQueryOptionImpl> list = (List<SystemQueryOptionImpl>) ctx.vEPE.accept(this);
        for (SystemQueryOptionImpl option : list) {
          expandItem.setExpandQueryOption(option);
        }
        contextExpandItemPath = contextExpandItemPathBU;
      }
    }

    return expandItem;

  }

  @Override
  public Object visitExpandPath(final ExpandPathContext ctx) {
    ExpandItemImpl expandItem = new ExpandItemImpl();

    UriResourceItImpl pathInfoIT = new UriResourceItImpl();

    UriResourceImplTyped tmp = (UriResourceImplTyped) contextUriInfo.getLastResourcePart();
    pathInfoIT.setCollection(tmp.isCollection()).setType(getLastType(tmp));

    // save context
    ExpandItemImpl contextExpandItemPathBU = contextExpandItemPath;
    UriInfoImpl uriInfoResourceBU = contextUriInfo;

    // set tmp context
    contextExpandItemPath = expandItem;
    contextUriInfo = new UriInfoImpl().setKind(UriInfoKind.resource);
    contextUriInfo.addPathInfo(pathInfoIT);

    super.visitExpandPath(ctx);
    expandItem.setResourcePath(contextUriInfo);

    // reset context
    contextUriInfo = uriInfoResourceBU;
    contextExpandItemPath = contextExpandItemPathBU;

    return expandItem;
  }

  @Override
  public Object visitExpandPathExtension(final ExpandPathExtensionContext ctx) {
    List<SystemQueryOptionImpl> list = new ArrayList<SystemQueryOptionImpl>();

    EdmType targetType = null;
    boolean isColl = false;
    if (contextExpandItemPath == null) {
      // use the type of the last resource path segement
      UriResourceImplTyped lastSegment = (UriResourceImplTyped) contextUriInfo.getLastResourcePart();
      targetType = getLastType(lastSegment);
      isColl = lastSegment.isCollection();
    } else {
      if (contextExpandItemPath.getPath() == null) {
        // use the type of the last resource path segement
        UriResourceImplTyped lastSegment = (UriResourceImplTyped) contextUriInfo.getLastResourcePart();
        targetType = getLastType(lastSegment);
        isColl = lastSegment.isCollection();
      } else {
        // use the type of the last ''expand'' path segement
        UriInfoImpl info = (UriInfoImpl) contextExpandItemPath.getPath();
        targetType = getLastType((UriResourceImplTyped) info.getLastResourcePart());
        isColl = ((UriResourceImplTyped) info.getLastResourcePart()).isCollection();
      }
    }

    contextTypes.push(new LastTypeColl(targetType, isColl));

    if (ctx.vC != null) {
      UriInfoImpl resourcePath = (UriInfoImpl) contextExpandItemPath.getPath();
      resourcePath.addPathInfo(new UriResourceCountImpl());

      for (ExpandCountOptionContext s : ctx.vlEOC) {
        list.add((SystemQueryOptionImpl) s.accept(this));
      }
    } else if (ctx.vR != null) {
      UriInfoImpl resourcePath = (UriInfoImpl) contextExpandItemPath.getPath();
      resourcePath.addPathInfo(new UriResourceRefImpl());

      for (ExpandRefOptionContext s : ctx.vlEOR) {
        list.add((SystemQueryOptionImpl) s.accept(this));
      }
    } else {
      for (ExpandOptionContext s : ctx.vlEO) {
        list.add((SystemQueryOptionImpl) s.accept(this));
      }
    }

    contextTypes.pop();
    return list;

  }

  @Override
  public Object visitFilter(final FilterContext ctx) {

    FilterOptionImpl filter = new FilterOptionImpl().setExpression((ExpressionImpl) ctx.children.get(2).accept(this));
    return filter;
  }

  @Override
  public ExpressionImpl visitFloorMethodCallExpr(final FloorMethodCallExprContext ctx) {
    return new MethodCallImpl()
        .setMethod(SupportedMethodCalls.FLOOR)
        .addParameter((ExpressionImpl) ctx.vE1.accept(this));
  }

  @Override
  public Object visitFormat(final FormatContext ctx) {
    FormatOptionImpl format = new FormatOptionImpl();

    TerminalNodeImpl c2 = (TerminalNodeImpl) ctx.children.get(2);
    if (c2.symbol.getType() == UriLexer.ATOM) {
      format.setValue("atom");
    } else if (c2.symbol.getType() == UriLexer.JSON) {
      format.setValue("json");
    } else if (c2.symbol.getType() == UriLexer.XML) {
      format.setValue("xml");
    } else if (c2.symbol.getType() == UriLexer.PCHARS) {
      if (ctx.getChildCount() == 2) {
        format.setValue(c2.getText());
      } else {
        format.setValue(c2.getText() + "/" + ctx.children.get(4).getText());
      }
    }
    String text = ctx.children.get(2).getText();
    if ( ctx.getChildCount()> 4) {
      text += ctx.children.get(3).getText();
      text += ctx.children.get(4).getText();
    }
    
    format.setText(text);
     

    return format;
  }

  @Override
  public ExpressionImpl visitFractionalsecondsMethodCallExpr(final FractionalsecondsMethodCallExprContext ctx) {
    return new MethodCallImpl()
        .setMethod(SupportedMethodCalls.FRACTIONALSECOND)
        .addParameter((ExpressionImpl) ctx.vE1.accept(this));
  }

  @Override
  public ExpressionImpl visitGeoLengthMethodCallExpr(final GeoLengthMethodCallExprContext ctx) {
    return new MethodCallImpl()
        .setMethod(SupportedMethodCalls.GEOLENGTH)
        .addParameter((ExpressionImpl) ctx.vE1.accept(this));
  }

  @Override
  public ExpressionImpl visitHourMethodCallExpr(final HourMethodCallExprContext ctx) {
    return new MethodCallImpl()
        .setMethod(SupportedMethodCalls.HOUR)
        .addParameter((ExpressionImpl) ctx.vE1.accept(this));
  }

  @Override
  public Object visitId(final IdContext ctx) {
    IdOptionImpl id = new IdOptionImpl();

    String text = ctx.children.get(2).getText();

    return id.setValue(text).setText(text);
  }

  @Override
  public ExpressionImpl visitIndexOfMethodCallExpr(final IndexOfMethodCallExprContext ctx) {
    return new MethodCallImpl()
        .setMethod(SupportedMethodCalls.INDEXOF)
        .addParameter((ExpressionImpl) ctx.vE1.accept(this))
        .addParameter((ExpressionImpl) ctx.vE2.accept(this));
  }

  @Override
  public Object visitInlinecount(final InlinecountContext ctx) {
    InlineCountOptionImpl inlineCount = new InlineCountOptionImpl();

    String text = ctx.children.get(2).getText();

    return inlineCount.setValue(text.equals("true") ? true : false).setText(text);
  }

  @Override
  public ExpressionImpl visitIntersectsMethodCallExpr(final IntersectsMethodCallExprContext ctx) {
    return new MethodCallImpl()
        .setMethod(SupportedMethodCalls.GEOLENGTH)
        .addParameter((ExpressionImpl) ctx.vE1.accept(this))
        .addParameter((ExpressionImpl) ctx.vE2.accept(this));
  }

  @Override
  public ExpressionImpl visitIsofExpr(final IsofExprContext ctx) {
    MethodCallImpl method = new MethodCallImpl();
    if (ctx.vE1 != null) {
      ExpressionImpl onExpression = (ExpressionImpl) ctx.vE1.accept(this);
      method.addParameter(onExpression);
    }

    FullQualifiedName fullName = new FullQualifiedName(ctx.vNS.getText(), ctx.vODI.getText());
    EdmType type = edm.getTypeDefinition(fullName);
    method.setMethod(SupportedMethodCalls.ISOF);
    method.addParameter(new TypeLiteralImpl().setType(type));

    return method;
  }

  @Override
  public ExpressionImpl visitLengthMethodCallExpr(final LengthMethodCallExprContext ctx) {
    return new MethodCallImpl()
        .setMethod(SupportedMethodCalls.LENGTH)
        .addParameter((ExpressionImpl) ctx.vE1.accept(this));
  }

  @Override
  public ExpressionImpl visitMaxDateTimeMethodCallExpr(final MaxDateTimeMethodCallExprContext ctx) {
    return new MethodCallImpl()
        .setMethod(SupportedMethodCalls.MINDATETIME);
  }

  @Override
  public Object visitMemberExpr(final MemberExprContext ctx) {

    UriInfoImpl uriInfoImplpath = new UriInfoImpl().setKind(UriInfoKind.resource);

    UriResourceItImpl pathInfoIT = new UriResourceItImpl();
    LastTypeColl lastTypeInfo = this.contextTypes.peek();
    pathInfoIT.setType(lastTypeInfo.type);
    pathInfoIT.setCollection(lastTypeInfo.isCollection);

    if (ctx.vIt != null || ctx.vIts != null) {
      pathInfoIT.setIsExplicitIT(true); // a $it prohibits unbound functions as member expression
    }

    uriInfoImplpath.addPathInfo(pathInfoIT);

    if (ctx.vPs != null) {
      // save the context
      UriInfoImpl backupUriInfoPath = contextUriInfo;

      // set temporary uriInfoPath
      contextUriInfo = uriInfoImplpath;

      ctx.vPs.accept(this);

      // reset context
      contextUriInfo = backupUriInfoPath;
    }

    if (ctx.vALL != null) {
      uriInfoImplpath.addPathInfo((UriResourcePartImpl) ctx.vALL.accept(this));
    }
    if (ctx.vANY != null) {
      uriInfoImplpath.addPathInfo((UriResourcePartImpl) ctx.vANY.accept(this));
    }

    return new MemberImpl()
        .setPath(uriInfoImplpath);
  }

  @Override
  public ExpressionImpl visitMinDateTimeMethodCallExpr(final MinDateTimeMethodCallExprContext ctx) {
    return new MethodCallImpl()
        .setMethod(SupportedMethodCalls.MINDATETIME);
  }

  @Override
  public ExpressionImpl visitMinuteMethodCallExpr(final MinuteMethodCallExprContext ctx) {
    return new MethodCallImpl()
        .setMethod(SupportedMethodCalls.MINUTE)
        .addParameter((ExpressionImpl) ctx.vE1.accept(this));
  }

  @Override
  public ExpressionImpl visitMonthMethodCallExpr(final MonthMethodCallExprContext ctx) {
    return new MethodCallImpl()
        .setMethod(SupportedMethodCalls.MONTH)
        .addParameter((ExpressionImpl) ctx.vE1.accept(this));
  }

  @Override
  public Object visitNameValueOptList(final NameValueOptListContext ctx) {

    // is key predicate
    if (ctx.vVO != null) {
      String valueText = ctx.vVO.vV.getText();
      ExpressionImpl expression = (ExpressionImpl) ctx.vVO.vV.accept(this);

      if (!(contextUriInfo.getLastResourcePart() instanceof UriResourceImplTyped)) {
        throw wrap(new UriParserSyntaxException("Paramterslist on untyped resource path segement not allowed"));
      }

      EdmEntityType entityType =
          (EdmEntityType) ((UriResourceImplTyped) contextUriInfo.getLastResourcePart()).getType();

      List<String> keyPredicates = entityType.getKeyPredicateNames();
      if (keyPredicates.size() == 1) {
        String keyName = keyPredicates.get(0);
        List<UriParameterImpl> list = new ArrayList<UriParameterImpl>();
        list.add(new UriParameterImpl().setName(keyName).setText(valueText).setExpression(expression));
        return list;
      }

      // If there is only a single key in the URI but there are more than one keys defined in the EDM, then reduce
      // The keylist with the keys defined as referential constrained.
      // TODO add support vor using refential constrains
      /*
       * if (contextUriInfo.getLastResourcePart() instanceof UriResourceNavigationPropertyImpl) {
       * UriResourceNavigationPropertyImpl nav =
       * (UriResourceNavigationPropertyImpl) contextUriInfo.getLastResourcePart();
       * nav.getNavigationProperty();
       * }
       */

      throw wrap(new UriParserSyntaxException(
          "for using a value only keyPredicate there must be exact ONE defined keyProperty"));

    } else {
      List<UriParameterImpl> list = new ArrayList<UriParameterImpl>();
      if (ctx.vNVL != null) {
        for (ParseTree c : ctx.vNVL.vlNVP) {
          list.add((UriParameterImpl) c.accept(this));
        }
      }
      return list;

    }
  }

  @Override
  public UriParameterImpl visitNameValuePair(final NameValuePairContext ctx) {
    UriParameterImpl uriParameter = new UriParameterImpl();
    uriParameter.setName(ctx.vODI.getText());

    if (ctx.vCOM != null) {
      uriParameter.setText(ctx.vCOM.getText());
      uriParameter.setExpression((ExpressionImpl) ctx.vCOM.accept(this));
    } else {
      uriParameter.setAlias(ctx.vALI.getText());
    }

    return uriParameter;
  }

  @Override
  public ExpressionImpl visitNowMethodCallExpr(final NowMethodCallExprContext ctx) {
    return new MethodCallImpl()
        .setMethod(SupportedMethodCalls.NOW);
  }

  @Override
  public Object visitOdataRelativeUriEOF(final OdataRelativeUriEOFContext ctx) {
    contextUriInfo = null;
    super.visitOdataRelativeUriEOF(ctx);
    return contextUriInfo;
  }

  @Override
  public Object visitOrderBy(final OrderByContext ctx) {

    OrderByOptionImpl orderBy = new OrderByOptionImpl();
    // TODO collect orders
    // .setExpression((Expression) ctx.children.get(2).accept(this));
    return orderBy;
  }

  @Override
  public Object visitPathSegment(final PathSegmentContext ctx) {

    if (contextUriInfo.getLastResourcePart() == null ||
        contextUriInfo.getLastResourcePart() instanceof UriResourceRootImpl) {
      readFirstPathInfoSegment(ctx);
    } else {
      readNextPathInfoSegment(ctx);
    }

    UriResourcePartImpl pathInfoSegment = (UriResourcePartImpl) contextUriInfo.getLastResourcePart();

    if (ctx.vlNVO.size() > 0) {
      // check for keyPredicates
      if (pathInfoSegment instanceof UriResourceImplKeyPred) {

        ((UriResourceImplKeyPred) pathInfoSegment)
            .setKeyPredicates((List<UriParameterImpl>) ctx.vlNVO.get(0).accept(this));
      } else {
        throw wrap(new UriParserSemanticException("Key properties not allowed"));
        // throw UriSemanticError.addKrepredicatesNotAllowed();
      }
    }

    return pathInfoSegment;
  }

  @Override
  public Object visitPathSegments(final PathSegmentsContext ctx) {
    // path segment
    for (PathSegmentContext it : ctx.vlPS) {
      it.accept(this);
    }

    // const segment
    if (ctx.vCS != null) {
      ctx.vCS.accept(this);
    }
    return null;
  }

  @Override
  public Object visitQueryOptions(final QueryOptionsContext ctx) {

    List<QueryOptionImpl> qpList = new ArrayList<QueryOptionImpl>();
    for (QueryOptionContext entityOption : ctx.vlQO) {
      qpList.add((QueryOptionImpl) entityOption.accept(this));
    }

    return qpList;
  }

  @Override
  public Object visitResourcePath(final ResourcePathContext ctx) {
    if (ctx.vAll != null) {
      contextUriInfo = new UriInfoImpl().setKind(UriInfoKind.all);
    } else if (ctx.vCJ != null) {
      ctx.vCJ.accept(this);
    } else if (ctx.vlPS != null) {
      UriInfoImpl uriInfoPath = new UriInfoImpl().setKind(UriInfoKind.resource);
      contextUriInfo = uriInfoPath;
      super.visitResourcePath(ctx); // visit all children of ctx
    }
    return contextUriInfo;
  }

  @Override
  public Object visitRootExpr(final RootExprContext ctx) {
    UriInfoImpl uriInfoImplpath = new UriInfoImpl().setKind(UriInfoKind.resource);

    UriResourceRootImpl pathInfoRoot = new UriResourceRootImpl();

    uriInfoImplpath.addPathInfo(pathInfoRoot);

    if (ctx.vPs != null) {
      // store the context uriInfoPath
      UriInfoImpl backupUriInfoPath = contextUriInfo;

      // set temporary uriInfoPath to collect the path information of the memberExpression
      contextUriInfo = uriInfoImplpath;

      ctx.vPs.accept(this);

      contextUriInfo = backupUriInfoPath;
    }
    return new MemberImpl()
        .setPath(uriInfoImplpath);

  }

  @Override
  public ExpressionImpl visitRoundMethodCallExpr(final RoundMethodCallExprContext ctx) {
    return new MethodCallImpl()
        .setMethod(SupportedMethodCalls.ROUND)
        .addParameter((ExpressionImpl) ctx.vE1.accept(this));
  }

  @Override
  public ExpressionImpl visitSecondMethodCallExpr(final SecondMethodCallExprContext ctx) {
    return new MethodCallImpl()
        .setMethod(SupportedMethodCalls.SECOND)
        .addParameter((ExpressionImpl) ctx.vE1.accept(this));
  }

  @Override
  public Object visitSelect(final SelectContext ctx) {
    List<SelectItemOptionImpl> selectItems = new ArrayList<SelectItemOptionImpl>();

    for (SelectItemContext si : ctx.vlSI) {
      selectItems.add((SelectItemOptionImpl) si.accept(this));
    }

    return new SelectOptionImpl().setSelectItems(selectItems);
  }

  @Override
  public Object visitSelectItem(final SelectItemContext ctx) {
    SelectItemOptionImpl selectItem = new SelectItemOptionImpl();

    contextSelectItem = selectItem;
    for (SelectSegmentContext si : ctx.vlSS) {
      si.accept(this);
    }
    contextSelectItem = null;

    return selectItem;
  }

  @Override
  public Object visitSelectSegment(final SelectSegmentContext ctx) {

    if (ctx.vS != null) {
      if (ctx.vNS != null) {
        String namespace = ctx.vNS.getText();
        namespace = namespace.substring(0, namespace.length() - 1);
        FullQualifiedName fullName = new FullQualifiedName(namespace, "*");
        contextSelectItem.addAllOperationsInSchema(fullName);
      } else {
        contextSelectItem.addStar();
      }
      return null;
    }

    String odi = ctx.vODI.getText();
    if (ctx.vNS == null) {

      EdmType prevType = contextSelectItem.getType();
      if (prevType == null) {
        prevType = this.contextTypes.peek().type;
      }

      if (!(prevType instanceof EdmStructuralType)) {
        throw wrap(new UriParserSemanticException("Previous select item is not a structural type"));
      }

      EdmStructuralType structType = (EdmStructuralType) prevType;
      EdmElement property = (EdmElement) structType.getProperty(odi);
      if (property == null) {
        throw wrap(new UriParserSemanticException("Previous select item has not property: " + odi));

      }

      // create new segment
      SelectSegmentImpl newSegment = new SelectSegmentImpl().setProperty(property);
      contextSelectItem.addSegment(newSegment);

      return this;

    } else {
      String namespace = ctx.vNS.getText();
      namespace = namespace.substring(0, namespace.length() - 1);

      FullQualifiedName fullName = new FullQualifiedName(namespace, odi);
      // contextSelectItem.addQualifiedThing(fullName);

      EdmType prevType = contextSelectItem.getType();
      if (prevType != null) { // context types required at least one property
        if (prevType instanceof EdmComplexType) {
          EdmComplexType ct = edm.getComplexType(fullName);
          if (ct != null) {
            if (((EdmStructuralType) prevType).compatibleTo(ct)) {
              SelectSegmentImpl lastSegment = contextSelectItem.getLastSegment();
              lastSegment.setTypeCast(ct);
              return this;
            }
          }
        }
      } else {
        prevType = this.contextTypes.peek().type;
        if (prevType instanceof EdmEntityType) {
          EdmEntityType et = edm.getEntityType(fullName);
          if (((EdmStructuralType) prevType).compatibleTo(et)) {
            contextSelectItem.setEntityTypeCast(et);
            return this;
          }
        }
      }

      FullQualifiedName finalTypeName = new FullQualifiedName(prevType.getNamespace(), prevType.getName());

      // check for action
      EdmAction action = edm.getAction(fullName, finalTypeName, null);
      // TODO verify that null ignores if it is a collection

      if (action != null) {
        contextSelectItem.addSegment(new SelectSegmentImpl().setAction(action));
      }

      // check for function
      EdmFunction function = edm.getFunction(fullName, finalTypeName, null, null);
      // TODO verify that null ignores if it is a collection

      if (function != null) {
        contextSelectItem.addSegment(new SelectSegmentImpl().setFunction(function));
      }

    }

    return null;
  }

  
  @Override
  public Object visitSkip(SkipContext ctx) {
    SkipOptionImpl skiptoken = new SkipOptionImpl();

    String text = ctx.children.get(2).getText();

    return skiptoken.setValue(text).setText(text);
  }

  @Override
  public Object visitSkiptoken(SkiptokenContext ctx) {
    SkiptokenOptionImpl skiptoken = new SkiptokenOptionImpl();

    String text = ctx.children.get(2).getText();

    return skiptoken.setValue(text).setText(text);
  }

  @Override
  public ExpressionImpl visitStartsWithMethodCallExpr(final StartsWithMethodCallExprContext ctx) {
    return new MethodCallImpl()
        .setMethod(SupportedMethodCalls.STARTSWITH)
        .addParameter((ExpressionImpl) ctx.vE1.accept(this));
  }

  @Override
  public ExpressionImpl visitSubstringMethodCallExpr(final SubstringMethodCallExprContext ctx) {
    MethodCallImpl ret = new MethodCallImpl();
    ret.setMethod(SupportedMethodCalls.SUBSTRING);
    ret.addParameter((ExpressionImpl) ctx.vE1.accept(this));
    ret.addParameter((ExpressionImpl) ctx.vE2.accept(this));

    if (ctx.vE3 != null) {
      ret.addParameter((ExpressionImpl) ctx.vE3.accept(this));
    }

    return ret;

  }

  @Override
  public ExpressionImpl visitTimeMethodCallExpr(final TimeMethodCallExprContext ctx) {
    return new MethodCallImpl()
        .setMethod(SupportedMethodCalls.TIME)
        .addParameter((ExpressionImpl) ctx.vE1.accept(this));
  }

  
  @Override
  public Object visitTop(TopContext ctx) {
    TopOptionImpl top = new TopOptionImpl();

    String text = ctx.children.get(2).getText();

    return top.setValue(text).setText(text);
  }

  @Override
  public ExpressionImpl visitToLowerMethodCallExpr(final ToLowerMethodCallExprContext ctx) {
    return new MethodCallImpl()
        .setMethod(SupportedMethodCalls.TOLOWER)
        .addParameter((ExpressionImpl) ctx.vE1.accept(this));
  }

  @Override
  public ExpressionImpl visitTotalOffsetMinutesMethodCallExpr(final TotalOffsetMinutesMethodCallExprContext ctx) {
    return new MethodCallImpl()
        .setMethod(SupportedMethodCalls.TOTALOFFSETMINUTES)
        .addParameter((ExpressionImpl) ctx.vE1.accept(this));
  }

  @Override
  public ExpressionImpl visitTotalsecondsMethodCallExpr(final TotalsecondsMethodCallExprContext ctx) {
    return new MethodCallImpl()
        .setMethod(SupportedMethodCalls.TOTALSECONDS)
        .addParameter((ExpressionImpl) ctx.vE1.accept(this));
  }

  @Override
  public ExpressionImpl visitToUpperMethodCallExpr(final ToUpperMethodCallExprContext ctx) {
    return new MethodCallImpl()
        .setMethod(SupportedMethodCalls.TOUPPER)
        .addParameter((ExpressionImpl) ctx.vE1.accept(this));
  }

  @Override
  public ExpressionImpl visitTrimMethodCallExpr(final TrimMethodCallExprContext ctx) {
    return new MethodCallImpl()
        .setMethod(SupportedMethodCalls.TRIM)
        .addParameter((ExpressionImpl) ctx.vE1.accept(this));
  }

  @Override
  public Object visitUnary(final UnaryContext ctx) {
    // TODO implement
    return super.visitUnary(ctx);
  }

  @Override
  public ExpressionImpl visitYearMethodCallExpr(final YearMethodCallExprContext ctx) {
    return new MethodCallImpl()
        .setMethod(SupportedMethodCalls.YEAR)
        .addParameter((ExpressionImpl) ctx.vE1.accept(this));
  }

  private ParseCancellationException wrap(final UriParserException uriParserException) {
    return new ParseCancellationException(uriParserException);
  }

}