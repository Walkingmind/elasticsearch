/*
 * Licensed to Elastic Search and Shay Banon under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. Elastic Search licenses this
 * file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
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

package org.elasticsearch.action.support.single.custom;

import org.elasticsearch.action.ActionRequestBuilder;
import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.client.internal.InternalGenericClient;

/**
 */
public abstract class SingleCustomOperationRequestBuilder<Request extends SingleCustomOperationRequest<Request>, Response extends ActionResponse, RequestBuilder extends SingleCustomOperationRequestBuilder<Request, Response, RequestBuilder>>
        extends ActionRequestBuilder<Request, Response, RequestBuilder> {

    protected SingleCustomOperationRequestBuilder(InternalGenericClient client, Request request) {
        super(client, request);
    }

    /**
     * Controls if the operation will be executed on a separate thread when executed locally.
     */
    @SuppressWarnings("unchecked")
    public final RequestBuilder setOperationThreaded(boolean threadedOperation) {
        request.setOperationThreaded(threadedOperation);
        return (RequestBuilder) this;
    }

    /**
     * if this operation hits a node with a local relevant shard, should it be preferred
     * to be executed on, or just do plain round robin. Defaults to <tt>true</tt>
     */
    @SuppressWarnings("unchecked")
    public final RequestBuilder setPreferLocal(boolean preferLocal) {
        request.setPreferLocal(preferLocal);
        return (RequestBuilder) this;
    }
}
