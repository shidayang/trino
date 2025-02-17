/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.server.security.oauth2;

import com.google.common.base.VerifyException;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.inject.Inject;
import io.airlift.json.JsonCodec;
import io.airlift.json.JsonCodecFactory;
import io.trino.dispatcher.DispatchExecutor;
import io.trino.server.security.ResourceSecurity;
import io.trino.server.security.oauth2.OAuth2TokenExchange.TokenPoll;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.BadRequestException;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.container.AsyncResponse;
import javax.ws.rs.container.Suspended;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static io.airlift.jaxrs.AsyncResponseHandler.bindAsyncResponse;
import static io.trino.server.security.ResourceSecurity.AccessType.PUBLIC;
import static io.trino.server.security.oauth2.OAuth2CallbackResource.CALLBACK_ENDPOINT;
import static io.trino.server.security.oauth2.OAuth2TokenExchange.MAX_POLL_TIME;
import static io.trino.server.security.oauth2.OAuth2TokenExchange.hashAuthId;
import static java.util.Objects.requireNonNull;
import static javax.ws.rs.core.MediaType.APPLICATION_JSON_TYPE;

@Path(OAuth2TokenExchangeResource.TOKEN_ENDPOINT)
public class OAuth2TokenExchangeResource
{
    static final String TOKEN_ENDPOINT = "/oauth2/token/";

    private static final JsonCodec<Map<String, Object>> MAP_CODEC = new JsonCodecFactory().mapJsonCodec(String.class, Object.class);

    private final OAuth2TokenExchange tokenExchange;
    private final OAuth2Service service;
    private final ListeningExecutorService responseExecutor;

    @Inject
    public OAuth2TokenExchangeResource(OAuth2TokenExchange tokenExchange, OAuth2Service service, DispatchExecutor executor)
    {
        this.tokenExchange = requireNonNull(tokenExchange, "tokenExchange is null");
        this.service = requireNonNull(service, "service is null");
        this.responseExecutor = executor.getExecutor();
    }

    @ResourceSecurity(PUBLIC)
    @Path("initiate/{authIdHash}")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response initiateTokenExchange(@PathParam("authIdHash") String authIdHash, @Context UriInfo uriInfo)
    {
        return service.startOAuth2Challenge(uriInfo.getBaseUri().resolve(CALLBACK_ENDPOINT), Optional.ofNullable(authIdHash));
    }

    @ResourceSecurity(PUBLIC)
    @Path("{authId}")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public void getAuthenticationToken(@PathParam("authId") UUID authId, @Suspended AsyncResponse asyncResponse, @Context HttpServletRequest request)
    {
        if (authId == null) {
            throw new BadRequestException();
        }

        // Do not drop the response from the cache on failure, as this would result in a
        // hang if the client retries the request. The response will timeout eventually.
        ListenableFuture<TokenPoll> tokenFuture = tokenExchange.getTokenPoll(authId);
        ListenableFuture<Response> responseFuture = Futures.transform(tokenFuture, OAuth2TokenExchangeResource::toResponse, responseExecutor);
        bindAsyncResponse(asyncResponse, responseFuture, responseExecutor)
                .withTimeout(MAX_POLL_TIME, pendingResponse(request));
    }

    private static Response toResponse(TokenPoll poll)
    {
        if (poll.getError().isPresent()) {
            return Response.ok(jsonMap("error", poll.getError().get()), APPLICATION_JSON_TYPE).build();
        }
        if (poll.getToken().isPresent()) {
            return Response.ok(jsonMap("token", poll.getToken().get()), APPLICATION_JSON_TYPE).build();
        }
        throw new VerifyException("invalid TokenPoll state");
    }

    private static Response pendingResponse(HttpServletRequest request)
    {
        return Response.ok(jsonMap("nextUri", request.getRequestURL()), APPLICATION_JSON_TYPE).build();
    }

    @ResourceSecurity(PUBLIC)
    @DELETE
    @Path("{authId}")
    public Response deleteAuthenticationToken(@PathParam("authId") UUID authId)
    {
        if (authId == null) {
            throw new BadRequestException();
        }

        tokenExchange.dropToken(authId);
        return Response
                .ok()
                .build();
    }

    public static String getTokenUri(UUID authId)
    {
        return TOKEN_ENDPOINT + authId;
    }

    public static String getInitiateUri(UUID authId)
    {
        return TOKEN_ENDPOINT + "initiate/" + hashAuthId(authId);
    }

    private static String jsonMap(String key, Object value)
    {
        return MAP_CODEC.toJson(ImmutableMap.of(key, value));
    }
}
