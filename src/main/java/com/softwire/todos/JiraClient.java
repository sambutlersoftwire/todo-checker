package com.softwire.todos;

import com.atlassian.httpclient.api.HttpClient;
import com.atlassian.jira.rest.client.api.JiraRestClient;
import com.atlassian.jira.rest.client.api.domain.Comment;
import com.atlassian.jira.rest.client.api.domain.Issue;
import com.atlassian.jira.rest.client.api.domain.SearchResult;
import com.atlassian.jira.rest.client.api.domain.ServerInfo;
import com.atlassian.jira.rest.client.internal.async.AbstractAsynchronousRestClient;
import com.atlassian.jira.rest.client.internal.async.AsynchronousJiraRestClientFactory;
import com.atlassian.jira.rest.client.internal.async.AsynchronousSearchRestClient;
import com.atlassian.jira.rest.client.internal.json.JsonParser;
import com.atlassian.jira.rest.client.internal.json.SearchResultJsonParser;
import com.atlassian.jira.rest.client.internal.json.gen.CommentJsonGenerator;
import com.atlassian.jira.rest.client.internal.json.gen.JsonGenerator;
import io.atlassian.util.concurrent.Promise;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.core.UriBuilder;
import java.lang.reflect.Field;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;
import java.util.concurrent.ExecutionException;

import static com.google.common.base.Preconditions.checkArgument;

/**
 * A thin wrapper around {@link JiraRestClient} which
 * a) enforces the `config.getWriteToJira()` flag
 * b) unwraps some hidden functions
 * c) caches issues to prevent re-fetching the same data
 */
public class JiraClient {

    private final Config config;
    private final JiraRestClient restClient;
    private Map<String, Issue> issuesByKey = new HashMap<>();
    private final Logger log = LoggerFactory.getLogger(getClass());
    private ServerInfo serverInfo;

    public JiraClient(Config config) throws URISyntaxException {
        this.config = config;
        URI serverUri = new URI(config.getJiraUrl());

        restClient = new AsynchronousJiraRestClientFactory()
                .createWithBasicHttpAuthentication(
                        serverUri,
                        config.getJiraUsername(),
                        config.getJiraPassword());
    }

    public ServerInfo getServerInfo() {
        if (serverInfo == null) {
            try {
                serverInfo = restClient.getMetadataClient().getServerInfo().get();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        return serverInfo;
    }

    public Issue getIssue(String key) throws Exception {
        if (config.getRestrictToSingleCardId() != null) {
            checkArgument(config.getRestrictToSingleCardId().equals(key));
        }

        Issue cached = issuesByKey.get(key);
        if (cached == null) {
            log.debug("Fetching card info for {}", key);
            cached = restClient.getIssueClient().getIssue(key).get();
            issuesByKey.put(key, cached);
        }
        return cached;
    }

    public void addComment(Issue issue, Comment comment) throws Exception {
        if (config.getWriteToJira()) {
            log.info("Adding comment to {}", issue.getKey());
            restClient.getIssueClient()
                    .addComment(issue.getCommentsUri(), comment)
                    .get();
        } else {
            log.info("Not adding comment to {}:\n{}", issue.getKey(), comment.getBody());
        }
    }

    public void updateComment(Comment comment) throws Exception {
        if (config.getWriteToJira()) {
            log.info("Updating comment {}", comment.getSelf());

            restClient.getIssueClient()
                    .updateComment(comment)
                    .get();
        } else {
            log.info("Not updating comment {}:\n{}", comment.getSelf(), comment.getBody());
        }
    }

    /**
     * This wrapper just exposes somes protected methods
     */
    private class ClientWrapper extends AbstractAsynchronousRestClient {
        public ClientWrapper(AbstractAsynchronousRestClient inner) throws Exception {
            super(getApacheClient(inner));
        }

        public <T> Promise<Void> put2(final URI uri, final T entity, final JsonGenerator<T> jsonGenerator) {
            return super.put(uri, entity, jsonGenerator);
        }

        public final Promise<Void> delete2(final URI uri) {
            return delete(uri);
        }

        public <T> Promise<T> getAndParse2(final URI uri, final JsonParser<?, T> parser) {
            return getAndParse(uri, parser);
        }
    }

    public void deleteComment(Issue issue, Comment comment) throws Exception {
        if (config.getWriteToJira()) {
            log.info("Deleting comment on {}", issue.getKey());

            restClient.getIssueClient()
                    .deleteComment(comment)
                    .get();
        } else {
            log.info("Not deleting comment to {}:\n{}", issue.getKey(), comment.getBody());
        }
    }

    public Set<Issue> searchIssuesWithComments(String jql) throws Exception {
        SearchResult searchResult = restClient.getSearchClient()
                .searchJql(jql, 1000, null, Collections.singleton("comment")).get();

        Set<Issue> issues = new LinkedHashSet<>();
        for (Issue issue : searchResult.getIssues()) {
            if (config.getRestrictToSingleCardId() == null || issue.getKey().equals(config.getRestrictToSingleCardId())) {
                issues.add(issue);
            }
        }
        return issues;
    }

    public String getViewUrl(Issue issue) throws Exception {
        return new URI(config.getJiraUrl()).resolve("browse/" + issue.getKey()).toString();
    }

    private HttpClient getApacheClient(AbstractAsynchronousRestClient client) throws Exception {
        Field field = AbstractAsynchronousRestClient.class.getDeclaredField("client");
        field.setAccessible(true);
        return (HttpClient) field.get(client);
    }

    public interface Config {
        String getRestrictToSingleCardId();

        boolean getWriteToJira();

        String getJiraUrl();

        String getJiraUsername();

        String getJiraPassword();
    }
}
