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

import java.util.Collection;
import java.util.stream.Collectors;

import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.comparison.IsEqualTo;
import dev.langchain4j.store.embedding.filter.comparison.IsGreaterThan;
import dev.langchain4j.store.embedding.filter.comparison.IsGreaterThanOrEqualTo;
import dev.langchain4j.store.embedding.filter.comparison.IsIn;
import dev.langchain4j.store.embedding.filter.comparison.IsLessThan;
import dev.langchain4j.store.embedding.filter.comparison.IsLessThanOrEqualTo;
import dev.langchain4j.store.embedding.filter.comparison.IsNotEqualTo;
import dev.langchain4j.store.embedding.filter.comparison.IsNotIn;
import dev.langchain4j.store.embedding.filter.logical.And;
import dev.langchain4j.store.embedding.filter.logical.Not;
import dev.langchain4j.store.embedding.filter.logical.Or;

public class OracleJSONPathFilterMapper {
  public String whereClause(Filter filter) {
    final String jsonExistsClause = "where json_exists(metadata, '$?(%s)')";
    return String.format(jsonExistsClause, map(filter));
  }

  private String map(Filter filter) {
    if (filter instanceof IsEqualTo) {
      IsEqualTo eq = (IsEqualTo) filter;
      return String.format("%s == %s", formatKey(eq.key()),
          formatValue(eq.comparisonValue()));
    } else if (filter instanceof IsNotEqualTo) {
      IsNotEqualTo ne = (IsNotEqualTo) filter;
      return String.format("%s != %s", formatKey(ne.key()),
          formatValue(ne.comparisonValue()));
    } else if (filter instanceof IsGreaterThan) {
      IsGreaterThan gt = (IsGreaterThan) filter;
      return String.format("%s > %s", formatKey(gt.key()),
          formatValue(gt.comparisonValue()));
    } else if (filter instanceof IsGreaterThanOrEqualTo) {
      IsGreaterThanOrEqualTo gte = (IsGreaterThanOrEqualTo) filter;
      return String.format("%s >= %s", formatKey(gte.key()),
          formatValue(gte.comparisonValue()));
    } else if (filter instanceof IsLessThan) {
      IsLessThan lt = (IsLessThan) filter;
      return String.format("%s < %s", formatKey(lt.key()),
          formatValue(lt.comparisonValue()));
    } else if (filter instanceof IsLessThanOrEqualTo) {
      IsLessThanOrEqualTo lte = (IsLessThanOrEqualTo) filter;
      return String.format("%s <= %s", formatKey(lte.key()),
          formatValue(lte.comparisonValue()));
    } else if (filter instanceof IsIn) {
      IsIn in = (IsIn) filter;
      return String.format("%s in %s", formatKey(in.key()),
          formatValues(in.comparisonValues()));
    } else if (filter instanceof IsNotIn) {
      IsNotIn ni = (IsNotIn) filter;
      return String.format("%s not in %s", formatKey(ni.key()),
          formatValues(ni.comparisonValues()));
    } else if (filter instanceof And) {
      And and = (And) filter;
      return String.format("%s && %s", map(and.left()), map(and.right()));
    } else if (filter instanceof Not) {
      Not not = (Not) filter;
      return String.format("nin (%s)", map(not.expression()));
    } else if (filter instanceof Or) {
      Or or = (Or) filter;
      return String.format("(%s || %s)", map(or.left()), map(or.right()));
    } else {
      throw new UnsupportedOperationException(
          "Unsupported filter type: " + filter.getClass().getName());
    }
  }

  private String formatKey(String key) {
    return "@." + key;
  }

  private String formatValue(Object v) {
    if (v instanceof String) {
      return String.format("\"%s\"", v);
    } else {
      return v.toString();
    }
  }

  String formatValues(Collection<?> values) {
    return "(" + values.stream().map(this::formatValue)
        .collect(Collectors.joining(",")) + ")";
  }
}