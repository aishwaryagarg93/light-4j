/*
 * Copyright (c) 2016 Network New Technologies Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.networknt.body;

import com.fasterxml.jackson.core.type.TypeReference;
import com.networknt.config.Config;
import com.networknt.handler.MiddlewareHandler;
import com.networknt.status.Status;
import com.networknt.utility.ModuleRegistry;
import io.undertow.Handlers;
import io.undertow.io.Receiver;
import io.undertow.server.Connectors;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.AttachmentKey;
import io.undertow.util.Headers;
import io.undertow.util.ImmediatePooledByteBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Scanner;

/**
 * This is a handler that parses the body into a Map or List if the input content type is JSON.
 * For other content type, don't parse it. In order to trigger this middleware, the content type
 * must be set in header for post, put and patch.
 *
 * Currently, it is only used in light-rest-4j framework as subsequent handler will use the parsed
 * body for further processing. Other frameworks like light-graphql-4j or light-hybrid-4j won't
 * need this middleware handler.
 *
 * Created by steve on 29/09/16.
 */
public class BodyHandler implements MiddlewareHandler {
    static final Logger logger = LoggerFactory.getLogger(BodyHandler.class);
    static final String CONTENT_TYPE_MISMATCH = "ERR10015";

    // request body will be parse during validation and it is attached to the exchange, in JSON,
    // it could be a map or list. So treat it as Object in the attachment.
    public static final AttachmentKey<Object> REQUEST_BODY = AttachmentKey.create(Object.class);

    public static final String CONFIG_NAME = "body";

    public static final BodyConfig config = (BodyConfig) Config.getInstance().getJsonObjectConfig(CONFIG_NAME, BodyConfig.class);

    private volatile HttpHandler next;

    public BodyHandler() {

    }

    /**
     * Check the header starts with application/json and parse it into map or list
     * based on the first character "{" or "[". Ignore other content type values.
     *
     * @param exchange HttpServerExchange
     * @throws Exception Exception
     */
    @Override
    public void handleRequest(final HttpServerExchange exchange) throws Exception {
        // parse the body to map or list if content type is application/json
        String contentType = exchange.getRequestHeaders().getFirst(Headers.CONTENT_TYPE);
        if (contentType != null && contentType.startsWith("application/json")) {
            exchange.getRequestReceiver().receiveFullBytes(new Receiver.FullBytesCallback() {
                @Override
                public void handle(HttpServerExchange httpServerExchange, byte[] bytes) {
                    // convert to json and attach the body
                    try {
                        if(bytes != null && bytes.length > 0) {
                            Object body;
                            if(bytes[0] == 123) { // handle { which is a map
                                body = Config.getInstance().getMapper().readValue(bytes, new TypeReference<HashMap<String, Object>>() {});
                            } else if(bytes[0] == 91) {  // handle [ which is an array
                                body = Config.getInstance().getMapper().readValue(bytes, new TypeReference<List<HashMap<String, Object>>>() {});
                            } else {
                                // error here. The content type in head doesn't match the body.
                                Status status = new Status(CONTENT_TYPE_MISMATCH, contentType);
                                exchange.setStatusCode(status.getStatusCode());
                                exchange.getResponseSender().send(status.toString());
                                return;
                            }
                            exchange.putAttachment(REQUEST_BODY, body);
                            if(config.keepStream) {
                                // put the bytes back to exchange so that subsequent handlers still have the body stream
                                Connectors.ungetRequestBytes(exchange, new ImmediatePooledByteBuffer(ByteBuffer.wrap(bytes)));
                            }
                        }
                    } catch (IOException e) {
                        logger.error("IOException: ", e);
                    }
                }
            });
        }
        // if content type is not application/json, then ignore it.
        next.handleRequest(exchange);
    }

    @Override
    public HttpHandler getNext() {
        return next;
    }

    @Override
    public MiddlewareHandler setNext(final HttpHandler next) {
        Handlers.handlerNotNull(next);
        this.next = next;
        return this;
    }

    @Override
    public boolean isEnabled() {
        return config.isEnabled();
    }

    @Override
    public void register() {
        ModuleRegistry.registerModule(BodyHandler.class.getName(), Config.getInstance().getJsonMapConfigNoCache(CONFIG_NAME), null);
    }
}
