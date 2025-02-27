/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */
package org.elasticsearch.search.source;

import org.apache.lucene.search.join.ScoreMode;
import org.elasticsearch.ExceptionsHelper;
import org.elasticsearch.action.search.SearchPhaseExecutionException;
import org.elasticsearch.index.query.InnerHitBuilder;
import org.elasticsearch.index.query.NestedQueryBuilder;
import org.elasticsearch.index.query.TermQueryBuilder;
import org.elasticsearch.search.SearchException;
import org.elasticsearch.search.SearchHits;
import org.elasticsearch.search.fetch.subphase.FetchSourceContext;
import org.elasticsearch.test.ESIntegTestCase;

import java.util.Collections;

import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertAcked;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertResponse;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

public class MetadataFetchingIT extends ESIntegTestCase {
    public void testSimple() {
        assertAcked(prepareCreate("test"));
        ensureGreen();

        prepareIndex("test").setId("1").setSource("field", "value").get();
        refresh();

        assertResponse(prepareSearch("test").storedFields("_none_").setFetchSource(false).setVersion(true), response -> {
            assertThat(response.getHits().getAt(0).getId(), nullValue());
            assertThat(response.getHits().getAt(0).getSourceAsString(), nullValue());
            assertThat(response.getHits().getAt(0).getVersion(), notNullValue());
        });

        assertResponse(prepareSearch("test").storedFields("_none_"), response -> {
            assertThat(response.getHits().getAt(0).getId(), nullValue());
            assertThat(response.getHits().getAt(0).getId(), nullValue());
            assertThat(response.getHits().getAt(0).getSourceAsString(), nullValue());
        });
    }

    public void testInnerHits() {
        assertAcked(prepareCreate("test").setMapping("nested", "type=nested"));
        ensureGreen();
        prepareIndex("test").setId("1").setSource("field", "value", "nested", Collections.singletonMap("title", "foo")).get();
        refresh();

        assertResponse(
            prepareSearch("test").storedFields("_none_")
                .setFetchSource(false)
                .setQuery(
                    new NestedQueryBuilder("nested", new TermQueryBuilder("nested.title", "foo"), ScoreMode.Total).innerHit(
                        new InnerHitBuilder().setStoredFieldNames(Collections.singletonList("_none_"))
                            .setFetchSourceContext(FetchSourceContext.DO_NOT_FETCH_SOURCE)
                    )
                ),
            response -> {
                assertThat(response.getHits().getTotalHits().value, equalTo(1L));
                assertThat(response.getHits().getAt(0).getId(), nullValue());
                assertThat(response.getHits().getAt(0).getSourceAsString(), nullValue());
                assertThat(response.getHits().getAt(0).getInnerHits().size(), equalTo(1));
                SearchHits hits = response.getHits().getAt(0).getInnerHits().get("nested");
                assertThat(hits.getTotalHits().value, equalTo(1L));
                assertThat(hits.getAt(0).getId(), nullValue());
                assertThat(hits.getAt(0).getSourceAsString(), nullValue());
            }
        );
    }

    public void testWithRouting() {
        assertAcked(prepareCreate("test"));
        ensureGreen();

        prepareIndex("test").setId("1").setSource("field", "value").setRouting("toto").get();
        refresh();

        assertResponse(prepareSearch("test").storedFields("_none_").setFetchSource(false), response -> {
            assertThat(response.getHits().getAt(0).getId(), nullValue());
            assertThat(response.getHits().getAt(0).field("_routing"), nullValue());
            assertThat(response.getHits().getAt(0).getSourceAsString(), nullValue());
        });
        assertResponse(prepareSearch("test").storedFields("_none_"), response -> {
            assertThat(response.getHits().getAt(0).getId(), nullValue());
            assertThat(response.getHits().getAt(0).getSourceAsString(), nullValue());
        });
    }

    public void testInvalid() {
        assertAcked(prepareCreate("test"));
        ensureGreen();

        indexDoc("test", "1", "field", "value");
        refresh();

        {
            SearchPhaseExecutionException exc = expectThrows(
                SearchPhaseExecutionException.class,
                prepareSearch("test").setFetchSource(true).storedFields("_none_")
            );
            Throwable rootCause = ExceptionsHelper.unwrap(exc, SearchException.class);
            assertNotNull(rootCause);
            assertThat(rootCause.getClass(), equalTo(SearchException.class));
            assertThat(rootCause.getMessage(), equalTo("[stored_fields] cannot be disabled if [_source] is requested"));
        }
        {
            SearchPhaseExecutionException exc = expectThrows(
                SearchPhaseExecutionException.class,
                prepareSearch("test").storedFields("_none_").addFetchField("field")
            );
            Throwable rootCause = ExceptionsHelper.unwrap(exc, SearchException.class);
            assertNotNull(rootCause);
            assertThat(rootCause.getClass(), equalTo(SearchException.class));
            assertThat(rootCause.getMessage(), equalTo("[stored_fields] cannot be disabled when using the [fields] option"));
        }
        {
            IllegalArgumentException exc = expectThrows(
                IllegalArgumentException.class,
                () -> prepareSearch("test").storedFields("_none_", "field1")
            );
            assertThat(exc.getMessage(), equalTo("cannot combine _none_ with other fields"));
        }
        {
            IllegalArgumentException exc = expectThrows(
                IllegalArgumentException.class,
                () -> prepareSearch("test").storedFields("_none_").storedFields("field1")
            );
            assertThat(exc.getMessage(), equalTo("cannot combine _none_ with other fields"));
        }
    }
}
