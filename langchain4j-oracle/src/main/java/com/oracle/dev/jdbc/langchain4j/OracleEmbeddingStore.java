/*
  Copyright (c) 2024, Oracle and/or its affiliates.

  This software is dual-licensed to you under the Universal Permissive License
  (UPL) 1.0 as shown at https://oss.oracle.com/licenses/upl or Apache License
  2.0 as shown at http://www.apache.org/licenses/LICENSE-2.0. You may choose
  either license.

  Licensed under the Apache License, Version 2.0 (the "License");
  you may not use this file except in compliance with the License.
  You may obtain a copy of the License at

     https://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
*/
/*
  Copyright (c) 2024, Oracle and/or its affiliates.

  This software is dual-licensed to you under the Universal Permissive License
  (UPL) 1.0 as shown at https://oss.oracle.com/licenses/upl or Apache License
  2.0 as shown at http://www.apache.org/licenses/LICENSE-2.0. You may choose
  either license.

  Licensed under the Apache License, Version 2.0 (the "License");
  you may not use this file except in compliance with the License.
  You may obtain a copy of the License at

     https://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
*/

package com.oracle.dev.jdbc.langchain4j;

import static dev.langchain4j.internal.Utils.getOrDefault;
import static dev.langchain4j.internal.Utils.isNotNullOrBlank;
import static dev.langchain4j.internal.Utils.isNullOrEmpty;
import static dev.langchain4j.internal.ValidationUtils.ensureNotBlank;
import static dev.langchain4j.internal.ValidationUtils.ensureNotNull;
import static dev.langchain4j.internal.ValidationUtils.ensureTrue;
import static java.util.Collections.singletonList;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import lombok.Builder;
import oracle.jdbc.OracleType;
import oracle.sql.json.OracleJsonObject;

public class OracleEmbeddingStore implements EmbeddingStore<TextSegment> {

  private static final Logger log = LoggerFactory
      .getLogger(OracleEmbeddingStore.class);

  // VECTOR SEARCH
  private static final Integer DEFAULT_DIMENSIONS = -1;
  private static final Integer DEFAULT_ACCURACY = -1;
  private static final OracleDistanceType DEFAULT_DISTANCE_TYPE = OracleDistanceType.COSINE;
  private static final OracleIndexType DEFAULT_INDEX_TYPE = OracleIndexType.IVF;

  // EMBEDDING STORE DETAILS / ORACLE DATABASE 23ai
  private final String table;
  private final DataSource dataSource;
  private final Integer accuracy;
  private final OracleDistanceType distanceType;
  private final OracleIndexType indexType;
  private final Boolean normalizeVectors;

  private final OracleJSONPathFilterMapper filterMapper = new OracleJSONPathFilterMapper();
  private final OracleDataAdapter dataAdapter = new OracleDataAdapter();

  @Builder
  public OracleEmbeddingStore(DataSource dataSource, String table,
      Integer dimension, Integer accuracy, OracleDistanceType distanceType,
      OracleIndexType indexType, Boolean useIndex, Boolean createTable,
      Boolean dropTableFirst, Boolean normalizeVectors) {
    this.dataSource = ensureNotNull(dataSource, "dataSource");
    this.table = ensureNotBlank(table, "table");
    this.accuracy = getOrDefault(accuracy, DEFAULT_ACCURACY);
    this.distanceType = getOrDefault(distanceType, DEFAULT_DISTANCE_TYPE);
    this.indexType = getOrDefault(indexType, DEFAULT_INDEX_TYPE);
    this.normalizeVectors = getOrDefault(normalizeVectors, false);

    useIndex = getOrDefault(useIndex, false);
    createTable = getOrDefault(createTable, true);
    dropTableFirst = getOrDefault(dropTableFirst, false);
    dimension = getOrDefault(dimension, DEFAULT_DIMENSIONS);

    initTable(dropTableFirst, createTable, useIndex, dimension);
  }

  /**
   * Adds a given embedding to the store.
   *
   * @param embedding
   *          The embedding to be added to the store.
   * @return The auto-generated ID associated with the added embedding.
   */
  @Override
  public String add(Embedding embedding) {
    String id = UUID.randomUUID().toString();
    addInternal(id, embedding, null);
    return id;
  }

  /**
   * Adds a given embedding to the store.
   *
   * @param id
   *          The unique identifier for the embedding to be added.
   * @param embedding
   *          The embedding to be added to the store.
   */
  @Override
  public void add(String id, Embedding embedding) {
    addInternal(id, embedding, null);
  }

  /**
   * Adds a given embedding and the corresponding content that has been embedded
   * to the store.
   *
   * @param embedding
   *          The embedding to be added to the store.
   * @param textSegment
   *          Original content that was embedded.
   * @return The auto-generated ID associated with the added embedding.
   */
  @Override
  public String add(Embedding embedding, TextSegment textSegment) {
    String id = UUID.randomUUID().toString();
    addInternal(id, embedding, textSegment);
    return id;
  }

  /**
   * Adds multiple embeddings to the store.
   *
   * @param embeddings
   *          A list of embeddings to be added to the store.
   * @return A list of auto-generated IDs associated with the added embeddings.
   */
  @Override
  public List<String> addAll(List<Embedding> embeddings) {
    List<String> ids = createIds(embeddings);
    addAllInternal(ids, embeddings, null);
    return ids;
  }

  /**
   * Adds multiple embeddings and their corresponding contents that have been
   * embedded to the store.
   *
   * @param embeddings
   *          A list of embeddings to be added to the store.
   * @param embedded
   *          A list of original contents that were embedded.
   * @return A list of auto-generated IDs associated with the added embeddings.
   */
  @Override
  public List<String> addAll(List<Embedding> embeddings,
      List<TextSegment> embedded) {
    List<String> ids = createIds(embeddings);
    addAllInternal(ids, embeddings, embedded);
    return ids;
  }

  /**
   * Removes a single embedding from the store by ID.
   *
   * @param id
   *          The unique ID of the embedding to be removed.
   */
  @Override
  public void remove(String id) {
    String deleteQuery = String.format("delete from %s where id = ?", table);
    try (Connection connection = dataSource.getConnection();
        PreparedStatement stmt = connection.prepareStatement(deleteQuery)) {
      stmt.setString(1, id);
      stmt.executeUpdate();
    } catch (SQLException e) {
      throw new RuntimeException("Failed to delete embedding " + id, e);
    }
  }

  /**
   * Removes all embeddings that match the specified IDs from the store.
   *
   * @param ids
   *          A collection of unique IDs of the embeddings to be removed.
   */
  @Override
  public void removeAll(Collection<String> ids) {
    String inClause = "("
        + ids.stream().map(id -> "?").collect(Collectors.joining(",")) + ")";
    String deleteQuery = String.format("delete from %s where id in %s", table,
        inClause);

    try (Connection connection = dataSource.getConnection();
        PreparedStatement stmt = connection.prepareStatement(deleteQuery)) {
      List<String> idList = new ArrayList<>(ids);
      for (int i = 0; i < idList.size(); i++) {
        stmt.setString(i + 1, idList.get(i));
      }
      stmt.executeUpdate();
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Removes all embeddings that match the specified {@link Filter} from the
   * store.
   *
   * @param filter
   *          The filter to be applied to the {@link Metadata} of the
   *          {@link TextSegment} during removal. Only embeddings whose
   *          {@code TextSegment}'s {@code Metadata} match the {@code Filter}
   *          will be removed.
   */
  @Override
  public void removeAll(Filter filter) {
    String deleteQuery = String.format("delete from %s", table);
    if (filter != null) {
      deleteQuery = String.format("%s %s", deleteQuery,
          filterMapper.whereClause(filter));
    }
    try (Connection connection = dataSource.getConnection();
        Statement stmt = connection.createStatement()) {
      stmt.executeUpdate(deleteQuery);
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Removes all embeddings from the store.
   */
  @Override
  public void removeAll() {
    try (Connection connection = dataSource.getConnection();
        Statement stmt = connection.createStatement()) {
      stmt.executeUpdate(String.format("truncate table %s", table));
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Searches for the most similar (closest in the embedding space)
   * {@link Embedding}s. <br>
   * All search criteria are defined inside the {@link EmbeddingSearchRequest}.
   * <br>
   * {@link EmbeddingSearchRequest#filter()} can be used to filter by
   * user/memory ID. Please note that not all {@link EmbeddingStore}
   * implementations support {@link Filter}ing.
   *
   * @param request
   *          A request to search in an {@link EmbeddingStore}. Contains all
   *          search criteria.
   * @return An {@link EmbeddingSearchResult} containing all found
   *         {@link Embedding}s.
   */
  @Override
  public EmbeddingSearchResult<TextSegment> search(
      EmbeddingSearchRequest request) {
    if (distanceType != OracleDistanceType.COSINE
        && distanceType != OracleDistanceType.DOT) {
      throw new UnsupportedOperationException(
          "Similarity search for distance type " + distanceType
              + " not supported");
    }
    if (!normalizeVectors) {
      throw new UnsupportedOperationException(
          "Similarity search vector normalization. See the 'normalizeVectors property of the OracleEmbeddingStore'");
    }

    Embedding requestEmbedding = request.queryEmbedding();
    int maxResults = request.maxResults();
    double minScore = request.minScore();
    String filterClause = request.filter() != null
        ? filterMapper.whereClause(request.filter()) + "\n"
        : "";
    List<EmbeddingMatch<TextSegment>> matches = new ArrayList<>();
    String searchQuery = String.format(
        "select * from\n" + "(\n"
            + "select id, content, metadata, embedding, (1 - %s) as score\n"
            + "from %s\n" + "%s" + "order by score desc\n" + ")\n"
            + "where score >= ?\n" + "%s",
        vectorDistanceClause(), table, filterClause,
        accuracyClause(maxResults));
    try (Connection connection = dataSource.getConnection();
        PreparedStatement stmt = connection.prepareStatement(searchQuery)) {
      stmt.setObject(1,
          dataAdapter.toVECTOR(requestEmbedding, normalizeVectors),
          OracleType.VECTOR.getVendorTypeNumber());
      stmt.setObject(2, minScore, OracleType.NUMBER.getVendorTypeNumber());
      try (ResultSet rs = stmt.executeQuery()) {
        while (rs.next()) {
          String id = rs.getString("id");
          double[] embeddings = rs.getObject("embedding", double[].class);
          Embedding embedding = new Embedding(
              dataAdapter.toFloatArray(embeddings));
          String content = rs.getObject("content", String.class);
          double score = rs.getObject("score", BigDecimal.class).doubleValue();
          TextSegment textSegment = null;
          if (isNotNullOrBlank(content)) {
            Map<String, Object> metadata = dataAdapter
                .toMap(rs.getObject("metadata", OracleJsonObject.class));
            textSegment = TextSegment.from(content, new Metadata(metadata));
          }
          matches.add(new EmbeddingMatch<>(score, id, embedding, textSegment));
        }
      }
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
    return new EmbeddingSearchResult<>(matches);
  }

  private String accuracyClause(int maxResults) {
    if (accuracy.equals(DEFAULT_ACCURACY)) {
      return String.format("fetch first %d rows only", maxResults);
    }
    return String.format(
        "fetch approximate first %d rows only with target accuracy %d",
        maxResults, accuracy);
  }

  private String vectorDistanceClause() {
    String clause = String.format("vector_distance(embedding, ?, %s)",
        distanceType.name());
    if (distanceType == OracleDistanceType.DOT) {
      clause = String.format("(1+%s)/2", clause);
    }
    return clause;
  }

  private void addInternal(String id, Embedding embedding,
      TextSegment embedded) {
    addAllInternal(singletonList(id), singletonList(embedding),
        embedded == null ? null : singletonList(embedded));
  }

  private void addAllInternal(List<String> ids, List<Embedding> embeddings,
      List<TextSegment> segments) {
    if (isNullOrEmpty(ids) || isNullOrEmpty(embeddings)) {
      log.info("Empty embeddings - none added");
      return;
    }
    ensureTrue(ids.size() == embeddings.size(),
        "ids and embeddings have different size");
    ensureTrue(segments == null || segments.size() == embeddings.size(),
        "segments and embeddings have different size");

    String upsert = String.format(
        "merge into %s target using (values(?, ?, ?, ?)) source (id, content, metadata, embedding) on (target.id = source.id)\n"
            + "when matched then update set target.content = source.content, target.metadata = source.metadata, target.embedding = source.embedding\n"
            + "when not matched then insert (target.id, target.content, target.metadata, target.embedding) values (source.id, source.content, source.metadata, source.embedding)",
        table);
    try (Connection connection = dataSource.getConnection();
        PreparedStatement stmt = connection.prepareStatement(upsert)) {
      for (int i = 0; i < ids.size(); i++) {
        stmt.setString(1, ids.get(i));
        if (segments != null && segments.get(i) != null) {
          TextSegment textSegment = segments.get(i);
          stmt.setString(2, textSegment.text());
          OracleJsonObject ojson = dataAdapter
              .toJSON(textSegment.metadata().toMap());
          stmt.setObject(3, ojson, OracleType.JSON.getVendorTypeNumber());
        } else {
          stmt.setString(2, "");
          stmt.setObject(3, dataAdapter.toJSON(null),
              OracleType.JSON.getVendorTypeNumber());
        }
        stmt.setObject(4,
            dataAdapter.toVECTOR(embeddings.get(i), normalizeVectors),
            OracleType.VECTOR.getVendorTypeNumber());
        stmt.addBatch();
      }
      stmt.executeBatch();
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  private List<String> createIds(List<Embedding> embeddings) {
    return embeddings.stream().map(e -> UUID.randomUUID().toString())
        .collect(Collectors.toList());
  }

  protected void initTable(Boolean dropTableFirst, Boolean createTable,
      Boolean useIndex, Integer dimension) {
    String query = "init";
    try (Connection connection = dataSource.getConnection();
        Statement stmt = connection.createStatement()) {
      if (dropTableFirst) {
        stmt.executeUpdate(
            String.format("drop table if exists %s purge", table));
      }
      if (createTable) {
        stmt.executeUpdate(String.format("create table if not exists %s (\n"
            + "id        varchar2(36) default sys_guid() primary key,\n"
            + "content   clob,\n" + "metadata  json,\n"
            + "embedding vector(%s,FLOAT32) annotations(Distance '%s', OracleIndexType '%s'))",
            table, getDimensionString(dimension), distanceType.name(),
            indexType.name()));
      }
      if (useIndex) {
        switch (indexType) {
          case IVF :
            stmt.executeUpdate(String.format(
                "create vector index if not exists vector_index_%s on %s (embedding)\n"
                    + "organization neighbor partitions\n" + "distance %s\n"
                    + "with target accuracy %d\n"
                    + "parameters (type IVF, neighbor partitions 10)",
                table, table, distanceType.name(), getAccuracy()));
            break;

          /*
           * TODO: Enable for 23.5 case HNSW:
           * this.jdbcTemplate.execute(String.format(""" create vector index if
           * not exists vector_index_%s on %s (embedding) organization inmemory
           * neighbor graph distance %s with target accuracy %d parameters (type
           * HNSW, neighbors 40, efconstruction 500)""", tableName, tableName,
           * distanceType.name(), searchAccuracy == DEFAULT_SEARCH_ACCURACY ? 95
           * : searchAccuracy)); break;
           */
        }
      }
    } catch (SQLException e) {
      throw new RuntimeException(
          String.format("Could not connect to database: %s", query), e);
    }
  }

  private String getDimensionString(Integer dimension) {
    return dimension.equals(DEFAULT_DIMENSIONS)
        ? "*"
        : String.valueOf(dimension);
  }

  private int getAccuracy() {
    return accuracy.equals(DEFAULT_ACCURACY) ? 95 : accuracy;
  }

}