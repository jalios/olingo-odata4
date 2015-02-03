/*
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
 */

package org.apache.olingo.server.core;

import java.util.List;

import org.apache.olingo.commons.api.edm.EdmFunction;
import org.apache.olingo.commons.api.edm.EdmReturnType;
import org.apache.olingo.server.api.OData;
import org.apache.olingo.server.api.ODataApplicationException;
import org.apache.olingo.server.api.ODataResponse;
import org.apache.olingo.server.api.ODataTranslatedException;
import org.apache.olingo.server.api.ServiceMetadata;
import org.apache.olingo.server.api.uri.UriParameter;
import org.apache.olingo.server.api.uri.UriResourceFunction;

public class FunctionRequest extends OperationRequest {
  private UriResourceFunction uriResourceFunction;

  public FunctionRequest(OData odata, ServiceMetadata serviceMetadata) {
    super(odata, serviceMetadata);
  }

  @Override
  public void execute(ServiceHandler handler, ODataResponse response)
      throws ODataTranslatedException, ODataApplicationException {

    if (!allowedMethod()) {
      methodNotAllowed();
    }

    // Functions always have return per 11.5.3
    if (isReturnTypePrimitive()) {
      // functions can not return a typed property in the context of entity, so
      // it must be treated
      // as value based response
      handler.invoke(this, getODataRequest().getMethod(),
          PrimitiveValueResponse.getInstance(this, response, isCollection(), getReturnType()));
    } else if (isReturnTypeComplex()) {
      handler.invoke(this, getODataRequest().getMethod(), PropertyResponse.getInstance(this, response,
          getReturnType().getType(), getContextURL(this.odata), isCollection()));
    } else {
      // returnType.getType().getKind() == EdmTypeKind.ENTITY
      if (isCollection()) {
        handler.invoke(this, getODataRequest().getMethod(),
            EntitySetResponse.getInstance(this, getContextURL(odata), response));
      } else {
        handler.invoke(this, getODataRequest().getMethod(),
            EntityResponse.getInstance(this, getContextURL(odata), response));
      }
    }
  }

  @Override
  public boolean allowedMethod() {
    // look for discussion about composable functions in odata-discussion
    // group with thread "Clarification on "Function" invocations"
    if (getFunction().isComposable()) {
      return (isGET() || isPATCH() || isDELETE() || isPOST() || isPUT());
    }
    return isGET();
  }

  public UriResourceFunction getUriResourceFunction() {
    return uriResourceFunction;
  }

  protected void setUriResourceFunction(UriResourceFunction uriResourceFunction) {
    this.uriResourceFunction = uriResourceFunction;
  }

  @Override
  public boolean isBound() {
    return this.uriResourceFunction.getFunctionImport() != null;
  }

  public EdmFunction getFunction() {
    return this.uriResourceFunction.getFunction();
  }

  public List<UriParameter> getParameters() {
    return this.uriResourceFunction.getParameters();
  }

  @Override
  public boolean isCollection() {
    return getFunction().getReturnType().isCollection();
  }

  @Override
  public EdmReturnType getReturnType() {
    return getFunction().getReturnType();
  }

  @Override
  public boolean hasReturnType() {
    // Part3 {12.1} says must have return type
    return true;
  }
}