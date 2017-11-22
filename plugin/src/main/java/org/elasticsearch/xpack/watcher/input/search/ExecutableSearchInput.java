/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.watcher.input.search;

import org.apache.logging.log4j.Logger;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.search.SearchType;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.bytes.BytesArray;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.util.concurrent.ThreadContext;
import org.elasticsearch.common.xcontent.NamedXContentRegistry;
import org.elasticsearch.common.xcontent.XContentHelper;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.script.Script;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.xpack.watcher.execution.WatchExecutionContext;
import org.elasticsearch.xpack.watcher.input.ExecutableInput;
import org.elasticsearch.xpack.watcher.support.XContentFilterKeysUtils;
import org.elasticsearch.xpack.watcher.support.search.WatcherSearchTemplateRequest;
import org.elasticsearch.xpack.watcher.support.search.WatcherSearchTemplateService;
import org.elasticsearch.xpack.watcher.watch.Payload;

import java.util.Map;

import static org.elasticsearch.xpack.ClientHelper.WATCHER_ORIGIN;
import static org.elasticsearch.xpack.ClientHelper.stashWithOrigin;
import static org.elasticsearch.xpack.watcher.input.search.SearchInput.TYPE;

/**
 * An input that executes search and returns the search response as the initial payload
 */
public class ExecutableSearchInput extends ExecutableInput<SearchInput, SearchInput.Result> {

    public static final SearchType DEFAULT_SEARCH_TYPE = SearchType.QUERY_THEN_FETCH;

    private final Client client;
    private final WatcherSearchTemplateService searchTemplateService;
    private final TimeValue timeout;

    public ExecutableSearchInput(SearchInput input, Logger logger, Client client,
                                 WatcherSearchTemplateService searchTemplateService, TimeValue defaultTimeout) {
        super(input, logger);
        this.client = client;
        this.searchTemplateService = searchTemplateService;
        this.timeout = input.getTimeout() != null ? input.getTimeout() : defaultTimeout;
    }

    @Override
    public SearchInput.Result execute(WatchExecutionContext ctx, Payload payload) {
        WatcherSearchTemplateRequest request = null;
        try {
            Script template = input.getRequest().getOrCreateTemplate();
            String renderedTemplate = searchTemplateService.renderTemplate(template, ctx, payload);
            request = new WatcherSearchTemplateRequest(input.getRequest(), new BytesArray(renderedTemplate));
            return doExecute(ctx, request);
        } catch (Exception e) {
            logger.error("failed to execute [{}] input for watch [{}], reason [{}]", TYPE, ctx.watch().id(), e.getMessage());
            return new SearchInput.Result(request, e);
        }
    }

    SearchInput.Result doExecute(WatchExecutionContext ctx, WatcherSearchTemplateRequest request) throws Exception {
        if (logger.isTraceEnabled()) {
            logger.trace("[{}] running query for [{}] [{}]", ctx.id(), ctx.watch().id(), request.getSearchSource().utf8ToString());
        }

        SearchResponse response;
        try (ThreadContext.StoredContext ignore = stashWithOrigin(client.threadPool().getThreadContext(), WATCHER_ORIGIN)) {
            response = client.search(searchTemplateService.toSearchRequest(request)).actionGet(timeout);
        }

        if (logger.isDebugEnabled()) {
            logger.debug("[{}] found [{}] hits", ctx.id(), response.getHits().getTotalHits());
            for (SearchHit hit : response.getHits()) {
                logger.debug("[{}] hit [{}]", ctx.id(), hit.getSourceAsMap());
            }
        }

        final Payload payload;
        if (input.getExtractKeys() != null) {
            BytesReference bytes = XContentHelper.toXContent(response, XContentType.JSON, false);
            // EMPTY is safe here because we never use namedObject
            try (XContentParser parser = XContentHelper.createParser(NamedXContentRegistry.EMPTY, bytes)) {
                Map<String, Object> filteredKeys = XContentFilterKeysUtils.filterMapOrdered(input.getExtractKeys(), parser);
                payload = new Payload.Simple(filteredKeys);
            }
        } else {
            payload = new Payload.XContent(response);
        }

        return new SearchInput.Result(request, payload);
    }
}
