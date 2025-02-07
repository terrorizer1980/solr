/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.solr.handler.sql;

import java.io.Closeable;
import java.io.IOException;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import com.google.common.collect.ImmutableMap;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rel.type.RelDataTypeImpl;
import org.apache.calcite.rel.type.RelDataTypeSystem;
import org.apache.calcite.rel.type.RelProtoDataType;
import org.apache.calcite.schema.Table;
import org.apache.calcite.schema.impl.AbstractSchema;
import org.apache.calcite.sql.type.SqlTypeFactoryImpl;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.impl.CloudSolrClient;
import org.apache.solr.client.solrj.io.SolrClientCache;
import org.apache.solr.client.solrj.request.LukeRequest;
import org.apache.solr.client.solrj.response.LukeResponse;
import org.apache.solr.common.cloud.Aliases;
import org.apache.solr.common.cloud.ClusterState;
import org.apache.solr.common.cloud.ZkStateReader;
import org.apache.solr.schema.DateValueFieldType;
import org.apache.solr.schema.DoubleValueFieldType;
import org.apache.solr.schema.FloatValueFieldType;
import org.apache.solr.schema.IntValueFieldType;
import org.apache.solr.schema.LongValueFieldType;
import org.apache.solr.security.PKIAuthenticationPlugin;

class SolrSchema extends AbstractSchema implements Closeable {
  final Properties properties;
  final SolrClientCache solrClientCache;
  private volatile boolean isClosed = false;

  SolrSchema(Properties properties, SolrClientCache solrClientCache) {
    super();
    this.properties = properties;
    this.solrClientCache = solrClientCache;
  }

  public SolrClientCache getSolrClientCache() {
    return solrClientCache;
  }

  @Override
  public void close() {
    isClosed = true;
  }

  public boolean isClosed() {
    return isClosed;
  }

  @Override
  protected Map<String, Table> getTableMap() {
    String zk = this.properties.getProperty("zk");
    CloudSolrClient cloudSolrClient = solrClientCache.getCloudSolrClient(zk);
    ZkStateReader zkStateReader = cloudSolrClient.getZkStateReader();
    ClusterState clusterState = zkStateReader.getClusterState();

    final ImmutableMap.Builder<String, Table> builder = ImmutableMap.builder();

    Set<String> collections = clusterState.getCollectionsMap().keySet();
    for (String collection : collections) {
      builder.put(collection, new SolrTable(this, collection));
    }

    Aliases aliases = zkStateReader.getAliases();
    for (String alias : aliases.getCollectionAliasListMap().keySet()) {
      // don't create duplicate entries
      if (!collections.contains(alias)) {
        builder.put(alias, new SolrTable(this, alias));
      }
    }

    return builder.build();
  }

  private Map<String, LukeResponse.FieldInfo> getFieldInfo(final String collection) {
    final String zk = this.properties.getProperty("zk");
    PKIAuthenticationPlugin.withServerIdentity(true);
    try {
      LukeRequest lukeRequest = new LukeRequest();
      lukeRequest.setNumTerms(0);
      return lukeRequest.process(solrClientCache.getCloudSolrClient(zk), collection).getFieldInfo();
    } catch (SolrServerException | IOException e) {
      throw new RuntimeException(e);
    } finally {
      PKIAuthenticationPlugin.withServerIdentity(false);
    }
  }

  private Map<String, LukeResponse.FieldTypeInfo> getFieldTypeInfo(final String collection) {
    final String zk = this.properties.getProperty("zk");
    PKIAuthenticationPlugin.withServerIdentity(true);
    try {
      LukeRequest lukeRequest = new LukeRequest();
      lukeRequest.setShowSchema(true); // for custom type info ...
      lukeRequest.setNumTerms(0);
      return lukeRequest.process(solrClientCache.getCloudSolrClient(zk), collection).getFieldTypeInfo();
    } catch (SolrServerException | IOException e) {
      throw new RuntimeException(e);
    } finally {
      PKIAuthenticationPlugin.withServerIdentity(false);
    }
  }

  RelProtoDataType getRelDataType(String collection) {
    // Temporary type factory, just for the duration of this method. Allowable
    // because we're creating a proto-type, not a type; before being used, the
    // proto-type will be copied into a real type factory.
    final RelDataTypeFactory typeFactory = new SqlTypeFactoryImpl(RelDataTypeSystem.DEFAULT);
    final RelDataTypeFactory.Builder fieldInfo = typeFactory.builder();
    Map<String, LukeResponse.FieldInfo> luceneFieldInfoMap = getFieldInfo(collection);

    Map<String, LukeResponse.FieldTypeInfo> fieldTypeInfoMap = null; // loaded lazily if needed
    Map<String, Class<?>> javaClassForTypeMap = new HashMap<>(); // local cache for custom field types we've already resolved

    for (Map.Entry<String, LukeResponse.FieldInfo> entry : luceneFieldInfoMap.entrySet()) {
      LukeResponse.FieldInfo luceneFieldInfo = entry.getValue();

      String luceneFieldType = luceneFieldInfo.getType();
      // SOLR-13414: Luke can return a field definition with no type in rare situations
      if (luceneFieldType == null) {
        continue;
      }

      RelDataType type;
      switch (luceneFieldType) {
        case "string":
          type = typeFactory.createJavaType(String.class);
          break;
        case "tint":
        case "tlong":
        case "int":
        case "long":
        case "pint":
        case "plong":
          type = typeFactory.createJavaType(Long.class);
          break;
        case "tfloat":
        case "tdouble":
        case "float":
        case "double":
        case "pfloat":
        case "pdouble":
          type = typeFactory.createJavaType(Double.class);
          break;
        case "pdate":
          type = typeFactory.createJavaType(Date.class);
          break;
        default:
          Class<?> javaClass = javaClassForTypeMap.get(luceneFieldType);
          if (javaClass == null) {
            if (fieldTypeInfoMap == null) {
              // lazily go to luke for the field type info ...
              fieldTypeInfoMap = getFieldTypeInfo(collection);
            }
            javaClass = guessJavaClassForFieldType(fieldTypeInfoMap.get(luceneFieldType));
            javaClassForTypeMap.put(luceneFieldType, javaClass);
          }
          type = typeFactory.createJavaType(javaClass);
      }

      /*
      EnumSet<FieldFlag> flags = luceneFieldInfo.parseFlags(luceneFieldInfo.getSchema());
      if(flags != null && flags.contains(FieldFlag.MULTI_VALUED)) {
        type = typeFactory.createArrayType(type, -1);
      }
      */

      fieldInfo.add(entry.getKey(), type).nullable(true);
    }
    fieldInfo.add("_query_", typeFactory.createJavaType(String.class));
    fieldInfo.add("score", typeFactory.createJavaType(Double.class));

    return RelDataTypeImpl.proto(fieldInfo.build());
  }

  private Class<?> guessJavaClassForFieldType(LukeResponse.FieldTypeInfo typeInfo) {
    Class<?> typeClass = null;
    if (typeInfo != null && !typeInfo.isTokenized() && typeInfo.getClassName() != null) {
      try {
        final Class<?> fieldTypeClass = getClass().getClassLoader().loadClass(typeInfo.getClassName());
        // a numeric type ... narrow down
        if (IntValueFieldType.class.isAssignableFrom(fieldTypeClass) || LongValueFieldType.class.isAssignableFrom(fieldTypeClass)) {
          typeClass = Long.class;
        } else if (FloatValueFieldType.class.isAssignableFrom(fieldTypeClass) || DoubleValueFieldType.class.isAssignableFrom(fieldTypeClass)) {
          typeClass = Double.class;
        } else if (DateValueFieldType.class.isAssignableFrom(fieldTypeClass)) {
          typeClass = Date.class;
        }
      } catch (ClassNotFoundException e) {
        throw new RuntimeException(e);
      }
    }
    // default to String if we could narrow it down by looking at the field type class
    return typeClass != null ? typeClass : String.class;
  }
}
