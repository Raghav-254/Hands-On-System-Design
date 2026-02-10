package com.emailservice.service;

import com.emailservice.model.Email;
import com.emailservice.storage.SearchStore;

import java.util.List;

/**
 * Email Search Service.
 *
 * Backed by Elasticsearch in production.
 * Supports full-text search across subject, body, and sender.
 */
public class SearchService {
    private final SearchStore searchStore;

    public SearchService(SearchStore searchStore) {
        this.searchStore = searchStore;
    }

    /** General keyword search across subject, body, sender */
    public List<Email> search(String userId, String query) {
        System.out.println("\n--- Searching emails for '" + query + "' ---");
        List<Email> results = searchStore.search(userId, query);
        System.out.println("  Found " + results.size() + " result(s)");
        for (Email e : results) {
            System.out.println("    " + e);
        }
        return results;
    }

    /** Search by sender */
    public List<Email> searchBySender(String userId, String sender) {
        System.out.println("\n--- Searching emails from '" + sender + "' ---");
        List<Email> results = searchStore.searchBySender(userId, sender);
        System.out.println("  Found " + results.size() + " result(s)");
        return results;
    }

    /** Search by subject keyword */
    public List<Email> searchBySubject(String userId, String keyword) {
        System.out.println("\n--- Searching emails with subject containing '" + keyword + "' ---");
        List<Email> results = searchStore.searchBySubject(userId, keyword);
        System.out.println("  Found " + results.size() + " result(s)");
        return results;
    }
}
