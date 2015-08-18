/**
 *  Copyright (C) 2015 Red Hat, Inc (jcasey@redhat.com)
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *          http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.commonjava.maven.ext.rest.handler;

import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;

/**
 * @author vdedik@redhat.com
 */
public class AddSuffixJettyHandler extends AbstractHandler implements Handler {
    private static final Logger LOGGER = LoggerFactory.getLogger(AddSuffixJettyHandler.class);
    private static final String DEFAULT_ENDPOINT = "/";
    private static final String DEFAULT_METHOD = "POST";
    private static final String DEFAULT_SUFFIX = "redhat-1";

    private final String endpoint;
    private final String method;
    private final String suffix;

    public AddSuffixJettyHandler() {
        this(DEFAULT_ENDPOINT, DEFAULT_METHOD, DEFAULT_SUFFIX);
    }

    public AddSuffixJettyHandler(String endpoint, String method, String suffix) {
        this.endpoint = endpoint;
        this.method = method;
        this.suffix = suffix;
    }

    @Override
    public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response)
            throws IOException, ServletException {

        LOGGER.info("Handling: {} {}", request.getMethod(), request.getPathInfo());

        if (target.equals(this.endpoint) && request.getMethod().equals(this.method)) {
            LOGGER.info("Handling with AddSuffixJettyHandler");

            // Get Request Body
            StringBuffer jb = new StringBuffer();
            String line = null;
            JSONObject requestBody = null;
            try {
                BufferedReader reader = request.getReader();
                while ((line = reader.readLine()) != null) {
                    jb.append(line);
                }

                requestBody = new JSONObject(jb.toString());
            } catch (Exception e) {
                LOGGER.warn("Error reading request body. {}", e.getMessage());
                return;
            }

            // Prepare Response
            JSONObject responseBody = new JSONObject();
            responseBody.put("project", requestBody.get("project") + "-" + this.suffix);
            if (requestBody.has("dependencies")) {
                JSONArray jsonDeps = requestBody.getJSONArray("dependencies");
                JSONArray responseDeps = new JSONArray();
                for (Integer i = 0; i < jsonDeps.length(); i++) {
                    String dep = (String) jsonDeps.get(i);
                    responseDeps.put(dep + "-" + this.suffix);
                }
                responseBody.put("dependencies", responseDeps);
            }

            // Set Response
            response.setContentType("application/json;charset=utf-8");
            response.setStatus(HttpServletResponse.SC_OK);
            baseRequest.setHandled(true);
            response.getWriter().println(responseBody.toString());
        } else {
            LOGGER.info("Handling: {} {} with AddSuffixJettyHandler failed," +
                            " because expected method was {} and endpoint {}",
                    request.getMethod(), request.getPathInfo(), this.method, this.endpoint);
        }
    }
}
