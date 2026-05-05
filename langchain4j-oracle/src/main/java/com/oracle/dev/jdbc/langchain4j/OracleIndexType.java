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

public enum OracleIndexType {

  /**
   * Performs exact nearest neighbor search.
   */
  NONE,

  /**
   * </p>
   * The default type of index created for an In-Memory Neighbor Graph vector
   * index is Hierarchical Navigable Small World (HNSW).
   * </p>
   *
   * <p>
   * With Navigable Small World (NSW), the idea is to build a proximity graph
   * where each vector in the graph connects to several others based on three
   * characteristics:
   * <ul>
   * <li>The distance between vectors</li>
   * <li>The maximum number of closest vector candidates considered at each step
   * of the search during insertion (EFCONSTRUCTION)</li>
   * <li>Within the maximum number of connections (NEIGHBORS) permitted per
   * vector</li>
   * </ul>
   * </p>
   *
   * @see <a href=
   *      "https://docs.oracle.com/en/database/oracle/oracle-database/23/vecse/understand-hierarchical-navigable-small-world-indexes.html">Oracle
   *      Database documentation</a>
   */
  HNSW,

  /**
   * <p>
   * The default type of index created for a Neighbor Partition vector index is
   * Inverted File Flat (IVF) vector index. The IVF index is a technique
   * designed to enhance search efficiency by narrowing the search area through
   * the use of neighbor partitions or clusters.
   * </p>
   *
   * * @see <a href=
   * "https://docs.oracle.com/en/database/oracle/oracle-database/23/vecse/understand-inverted-file-flat-vector-indexes.html">Oracle
   * Database documentation</a>
   */
  IVF;

}
