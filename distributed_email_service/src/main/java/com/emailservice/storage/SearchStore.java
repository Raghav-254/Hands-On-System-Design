package com.emailservice.storage;

import com.emailservice.model.Email;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Simulates Elasticsearch for full-text email search.
 *
 * In production: Elasticsearch cluster with inverted index.
 * Partitioned by user_id for query isolation.
 *
 * Indexed fields: subject, body, from, to
 */
public class SearchStore {
    // user_id → list of indexed emails
    private final Map<String, List<Email>> index = new LinkedHashMap<>();

    /** Index an email for search (called after email is stored) */
    public void indexEmail(String userId, Email email) {
        index.computeIfAbsent(userId, k -> new ArrayList<>()).add(email);
    }

    /** Search emails by keyword (searches subject, body, from) */
    public List<Email> search(String userId, String query) {
        String lowerQuery = query.toLowerCase();
        List<Email> emails = index.getOrDefault(userId, List.of());
        return emails.stream()
                .filter(e -> e.getSubject().toLowerCase().contains(lowerQuery)
                        || e.getBody().toLowerCase().contains(lowerQuery)
                        || e.getFromUser().toLowerCase().contains(lowerQuery))
                .sorted((a, b) -> b.getTimestamp().compareTo(a.getTimestamp()))
                .collect(Collectors.toList());
    }

    /** Search by sender */
    public List<Email> searchBySender(String userId, String sender) {
        List<Email> emails = index.getOrDefault(userId, List.of());
        return emails.stream()
                .filter(e -> e.getFromUser().equalsIgnoreCase(sender))
                .collect(Collectors.toList());
    }

    /** Search by subject */
    public List<Email> searchBySubject(String userId, String subjectKeyword) {
        String lowerKeyword = subjectKeyword.toLowerCase();
        List<Email> emails = index.getOrDefault(userId, List.of());
        return emails.stream()
                .filter(e -> e.getSubject().toLowerCase().contains(lowerKeyword))
                .collect(Collectors.toList());
    }
}
