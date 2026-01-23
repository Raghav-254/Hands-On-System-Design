package com.newsfeed.storage;

import com.newsfeed.models.Post;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * PostDB - Simulates the Post Database.
 * 
 * In production, this would be:
 * - MySQL/PostgreSQL for structured data
 * - Or Cassandra for high write throughput
 * 
 * Stores:
 * - All posts with full content
 * - Indexed by post_id and author_id
 */
public class PostDB {
    
    // postId -> Post
    private final Map<Long, Post> postsById = new ConcurrentHashMap<>();
    
    // authorId -> List of postIds (for author's timeline)
    private final Map<Long, List<Long>> postsByAuthor = new ConcurrentHashMap<>();
    
    /**
     * Save a new post
     */
    public void save(Post post) {
        postsById.put(post.getPostId(), post);
        postsByAuthor.computeIfAbsent(post.getAuthorId(), k -> 
            Collections.synchronizedList(new ArrayList<>())).add(0, post.getPostId());
        
        System.out.println(String.format("  [PostDB] Saved post %d by user %d", 
            post.getPostId(), post.getAuthorId()));
    }
    
    /**
     * Get post by ID
     */
    public Post getById(long postId) {
        return postsById.get(postId);
    }
    
    /**
     * Get multiple posts by IDs (batch fetch)
     */
    public List<Post> getByIds(List<Long> postIds) {
        return postIds.stream()
            .map(postsById::get)
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
    }
    
    /**
     * Get posts by author (for user's own timeline)
     */
    public List<Post> getByAuthor(long authorId, int limit) {
        List<Long> postIds = postsByAuthor.getOrDefault(authorId, Collections.emptyList());
        return postIds.stream()
            .limit(limit)
            .map(postsById::get)
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
    }
    
    /**
     * Get recent posts from multiple authors (for fan-out on read)
     */
    public List<Post> getRecentPostsByAuthors(Set<Long> authorIds, int limitPerAuthor) {
        List<Post> allPosts = new ArrayList<>();
        
        for (Long authorId : authorIds) {
            List<Long> postIds = postsByAuthor.getOrDefault(authorId, Collections.emptyList());
            postIds.stream()
                .limit(limitPerAuthor)
                .map(postsById::get)
                .filter(Objects::nonNull)
                .forEach(allPosts::add);
        }
        
        // Sort by creation time (newest first)
        allPosts.sort((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()));
        
        return allPosts;
    }
    
    /**
     * Delete a post
     */
    public void delete(long postId) {
        Post post = postsById.remove(postId);
        if (post != null) {
            List<Long> authorPosts = postsByAuthor.get(post.getAuthorId());
            if (authorPosts != null) {
                authorPosts.remove(postId);
            }
        }
    }
    
    /**
     * Get total post count
     */
    public int getTotalPosts() {
        return postsById.size();
    }
}

