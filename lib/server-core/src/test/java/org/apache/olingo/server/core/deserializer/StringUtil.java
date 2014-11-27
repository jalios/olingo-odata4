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
package org.apache.olingo.server.core.deserializer;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;

import org.apache.olingo.commons.api.ODataRuntimeException;
import org.apache.olingo.server.core.deserializer.batch.BufferedReaderIncludingLineEndings;

public class StringUtil {
  
  
  public static String toString(final InputStream in) throws Exception {
    final StringBuilder builder = new StringBuilder();
    final BufferedReaderIncludingLineEndings reader = new BufferedReaderIncludingLineEndings(new InputStreamReader(in));
    String currentLine;
    
    while((currentLine = reader.readLine()) != null) {
      builder.append(currentLine);
    }
    
    reader.close();
    
    return builder.toString();
  }

  public static InputStream toInputStream(final String string) {
    try {
      return new ByteArrayInputStream(string.getBytes("UTF-8"));
    } catch (UnsupportedEncodingException e) {
      throw new ODataRuntimeException("Charset UTF-8 not found");
    }
  }
}