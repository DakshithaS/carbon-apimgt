/*
 * Copyright (c) 2025, WSO2 LLC. (http://www.wso2.com).
 *
 * WSO2 LLC. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.carbon.apimgt.gateway.mcp.request;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.lang.reflect.Type;

/**
 * This class is used to deserialize a JSON element into a McpRequest object.
 * It implements the JsonDeserializer interface from the Gson library.
 * A custom deserializer was required to handle the id field, which can be either a string or an integer.
 */

public class MCPRequestDeserializer implements JsonDeserializer<McpRequest> {
    private static final Log log = LogFactory.getLog(MCPRequestDeserializer.class);

    /**
     * Deserialize a JSON element into a McpRequest object.
     *
     * @param json The JSON element to deserialize.
     * @param typeOfT The type of the object to deserialize into.
     * @param context The deserialization context.
     * @return A McpRequest object.
     * @throws JsonParseException If the JSON is not in the expected format.
     */

    @Override
    public McpRequest deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context)
            throws JsonParseException {
        if (log.isDebugEnabled()) {
            log.debug("Starting deserialization of McpRequest");
        }

        JsonObject obj = json.getAsJsonObject();
        McpRequest mcpRequest = new McpRequest(null);

        // Requests MUST include a string or integer ID (2025-06-18 spec)
        JsonElement idElement = obj.get("id");
        if (idElement != null && idElement.isJsonPrimitive()) {
            JsonPrimitive prim = idElement.getAsJsonPrimitive();
            if (prim.isString()) {
                mcpRequest.setId(prim.getAsString());
                if (log.isDebugEnabled()) {
                    log.debug("Set string ID: " + prim.getAsString());
                }
            } else if (prim.isNumber()) {
                long val = prim.getAsLong();
                mcpRequest.setId(val);
                if (log.isDebugEnabled()) {
                    log.debug("Set integer ID: " + val);
                }
            }
        } else {
            if (log.isDebugEnabled()) {
                log.debug("ID element is missing in McpRequest");
            }
        }

        // Let Gson handle the rest
        mcpRequest.setMethod(context.deserialize(obj.get("method"), String.class));
        mcpRequest.setParams(context.deserialize(obj.get("params"), Params.class));

        if (log.isDebugEnabled()) {
            log.debug("Successfully deserialized McpRequest with method: " + mcpRequest.getMethod());
        }

        return mcpRequest;
    }
}
