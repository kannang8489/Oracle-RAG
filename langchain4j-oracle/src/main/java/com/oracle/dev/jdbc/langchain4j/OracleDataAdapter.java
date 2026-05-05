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

import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

import dev.langchain4j.data.embedding.Embedding;
import oracle.jdbc.driver.json.tree.OracleJsonStringImpl;
import oracle.sql.VECTOR;
import oracle.sql.json.OracleJsonFactory;
import oracle.sql.json.OracleJsonObject;
import oracle.sql.json.OracleJsonValue;

class OracleDataAdapter {
  float[] toFloatArray(double[] vector) {
    float[] result = new float[vector.length];
    for (int i = 0; i < vector.length; i++) {
      result[i] = (float) vector[i];
    }
    return result;
  }

  Map<String, Object> toMap(OracleJsonObject ojson) {
    Map<String, Object> map = new HashMap<>();
    for (String key : ojson.keySet()) {
      OracleJsonValue jsonValue = ojson.get(key);
      if (jsonValue instanceof OracleJsonStringImpl) {
        map.put(key, ((OracleJsonStringImpl) jsonValue).getString());
      } else {
        map.put(key, ojson.get(key));
      }
    }
    return map;
  }

  VECTOR toVECTOR(Embedding embedding, boolean normalizeVector)
      throws SQLException {
    float[] vector = embedding.vector();
    if (normalizeVector) {
      vector = normalize(vector);
    }
    return VECTOR.ofFloat32Values(vector);
  }

  OracleJsonObject toJSON(Map<String, Object> metadata) {
    OracleJsonObject ojson = new OracleJsonFactory().createObject();
    if (metadata == null) {
      return ojson;
    }
    for (Map.Entry<String, Object> entry : metadata.entrySet()) {
      final Object o = entry.getValue();
      // Each branch must be exclusive — no fall-through to String.valueOf()
      if (o instanceof String) {
        ojson.put(entry.getKey(), (String) o);
      } else if (o instanceof Integer) {
        ojson.put(entry.getKey(), (Integer) o);
      } else if (o instanceof Float) {
        ojson.put(entry.getKey(), (Float) o);
      } else if (o instanceof Double) {
        ojson.put(entry.getKey(), (Double) o);
      } else if (o instanceof Boolean) {
        ojson.put(entry.getKey(), (Boolean) o);
      } else {
        // Fallback for any other type — store as string
        ojson.put(entry.getKey(), String.valueOf(o));
      }
    }
    return ojson;
  }

  private float[] normalize(float[] v) {
    double squaredSum = 0d;

    for (float e : v) {
      squaredSum += e * e;
    }

    final float magnitude = (float) Math.sqrt(squaredSum);

    if (magnitude > 0) {
      final float multiplier = 1f / magnitude;
      final int length = v.length;
      for (int i = 0; i < length; i++) {
        v[i] *= multiplier;
      }
    }

    return v;
  }
}