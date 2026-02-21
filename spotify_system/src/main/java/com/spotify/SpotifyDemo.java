package com.spotify;

import com.spotify.model.*;
import com.spotify.storage.*;
import com.spotify.service.*;
import com.spotify.event.EventBus;

import java.util.List;
import java.util.Map;

public class SpotifyDemo {

    public static void main(String[] args) {
        System.out.println("=".repeat(70));
        System.out.println("           SPOTIFY SYSTEM DESIGN — DEMO");
        System.out.println("=".repeat(70));

        MusicMetadataDB metadataDB = new MusicMetadataDB();
        PlayHistoryStore playHistory = new PlayHistoryStore();
        SearchIndex searchIndex = new SearchIndex();
        CDNCache cdn = new CDNCache(10);
        EventBus eventBus = new EventBus();

        eventBus.subscribe(playHistory::recordPlay);

        StreamingService streamingService = new StreamingService(metadataDB, cdn, eventBus);
        SearchService searchService = new SearchService(searchIndex);
        PlaylistService playlistService = new PlaylistService(metadataDB);
        RecommendationService recoService = new RecommendationService(playHistory, metadataDB);

        seedData(metadataDB, searchIndex);

        demo1_StreamingSong(streamingService, cdn);
        demo2_AdaptiveBitrate(metadataDB, streamingService);
        demo3_CDNCaching(streamingService, cdn);
        demo4_Search(searchService);
        demo5_FuzzySearch(searchService);
        demo6_PlaylistCRUD(playlistService, metadataDB);
        demo7_CollaborativePlaylistConflict(playlistService);
        demo8_PlayHistoryAndRecommendations(streamingService, playHistory, recoService, metadataDB);
    }

    private static void seedData(MusicMetadataDB db, SearchIndex index) {
        db.addArtist(new Artist("art_1", "Queen", "queen.jpg", "British rock band"));
        db.addArtist(new Artist("art_2", "The Beatles", "beatles.jpg", "English rock band"));
        db.addArtist(new Artist("art_3", "Adele", "adele.jpg", "English singer"));
        db.addArtist(new Artist("art_4", "Ed Sheeran", "ed.jpg", "English singer-songwriter"));
        db.addArtist(new Artist("art_5", "Taylor Swift", "taylor.jpg", "American singer-songwriter"));

        db.addAlbum(new Album("alb_1", "art_1", "A Night at the Opera", 1975, "opera.jpg"));
        db.addAlbum(new Album("alb_2", "art_2", "Abbey Road", 1969, "abbey.jpg"));
        db.addAlbum(new Album("alb_3", "art_3", "21", 2011, "21.jpg"));
        db.addAlbum(new Album("alb_4", "art_4", "Divide", 2017, "divide.jpg"));
        db.addAlbum(new Album("alb_5", "art_5", "1989", 2014, "1989.jpg"));
        db.addAlbum(new Album("alb_6", "art_1", "News of the World", 1977, "news.jpg"));

        Song[] songs = {
            new Song("s_1", "Bohemian Rhapsody", "art_1", "Queen", "alb_1", "A Night at the Opera", "ROCK", 354000),
            new Song("s_2", "We Will Rock You", "art_1", "Queen", "alb_6", "News of the World", "ROCK", 122000),
            new Song("s_3", "We Are the Champions", "art_1", "Queen", "alb_6", "News of the World", "ROCK", 179000),
            new Song("s_4", "Come Together", "art_2", "The Beatles", "alb_2", "Abbey Road", "ROCK", 259000),
            new Song("s_5", "Here Comes the Sun", "art_2", "The Beatles", "alb_2", "Abbey Road", "ROCK", 185000),
            new Song("s_6", "Something", "art_2", "The Beatles", "alb_2", "Abbey Road", "ROCK", 182000),
            new Song("s_7", "Rolling in the Deep", "art_3", "Adele", "alb_3", "21", "POP", 228000),
            new Song("s_8", "Someone Like You", "art_3", "Adele", "alb_3", "21", "POP", 285000),
            new Song("s_9", "Shape of You", "art_4", "Ed Sheeran", "alb_4", "Divide", "POP", 233000),
            new Song("s_10", "Shake It Off", "art_5", "Taylor Swift", "alb_5", "1989", "POP", 219000),
            new Song("s_11", "Blank Space", "art_5", "Taylor Swift", "alb_5", "1989", "POP", 231000),
            new Song("s_12", "Love Story", "art_5", "Taylor Swift", "alb_5", "1989", "COUNTRY", 235000),
        };

        songs[0].setPlayCount(2_500_000_000L);
        songs[1].setPlayCount(1_800_000_000L);
        songs[8].setPlayCount(3_500_000_000L);
        songs[6].setPlayCount(2_000_000_000L);
        songs[9].setPlayCount(1_500_000_000L);

        for (Song s : songs) {
            db.addSong(s);
        }
        index.indexAll(db.getAllSongs());

        db.addUser(new User("u_1", "Alice", "alice@mail.com", User.Subscription.PREMIUM));
        db.addUser(new User("u_2", "Bob", "bob@mail.com", User.Subscription.FREE));
        db.addUser(new User("u_3", "Charlie", "charlie@mail.com", User.Subscription.PREMIUM));
    }

    // ──────────────────────────────────────────────────────────────────
    // DEMO 1: Streaming a song (pre-signed CDN URL)
    // ──────────────────────────────────────────────────────────────────
    private static void demo1_StreamingSong(StreamingService streaming, CDNCache cdn) {
        System.out.println("\n" + "─".repeat(70));
        System.out.println("DEMO 1: Streaming a Song (Pre-signed CDN URL)");
        System.out.println("─".repeat(70));
        System.out.println("Concept: Server never touches audio bytes. It generates a signed");
        System.out.println("CDN URL; client fetches audio directly from CDN.\n");

        StreamingService.StreamResponse response = streaming.playSong("s_1", "u_1", "high");
        System.out.println("Request:  POST /songs/s_1/play (user=Alice, network=high)");
        System.out.println("Response: " + response);
        System.out.println("  URL:     " + response.streamUrl);
        System.out.println("  Bitrate: " + response.bitrate + " kbps");
        System.out.println("  Song:    " + response.song);

        System.out.println("\nClient fetches audio chunk from CDN:");
        String s3Path = response.song.getS3Path(response.bitrate);
        System.out.println("  " + streaming.fetchChunkFromCDN(s3Path));
        System.out.println("  " + streaming.fetchChunkFromCDN(s3Path));
        System.out.println("\n  CDN state: " + cdn);
    }

    // ──────────────────────────────────────────────────────────────────
    // DEMO 2: Adaptive bitrate (Premium vs Free, network quality)
    // ──────────────────────────────────────────────────────────────────
    private static void demo2_AdaptiveBitrate(MusicMetadataDB db, StreamingService streaming) {
        System.out.println("\n" + "─".repeat(70));
        System.out.println("DEMO 2: Adaptive Bitrate Selection");
        System.out.println("─".repeat(70));
        System.out.println("Concept: Bitrate = min(user subscription max, network capacity).");
        System.out.println("Premium users get up to 320kbps; Free users capped at 128kbps.\n");

        String[][] cases = {
            {"u_1", "high",   "Alice (PREMIUM)", },
            {"u_1", "low",    "Alice (PREMIUM)", },
            {"u_2", "high",   "Bob (FREE)",      },
            {"u_2", "medium", "Bob (FREE)",      },
        };

        System.out.printf("  %-22s %-10s → %-10s%n", "User", "Network", "Bitrate");
        System.out.println("  " + "─".repeat(50));
        for (String[] c : cases) {
            StreamingService.StreamResponse r = streaming.playSong("s_1", c[0], c[1]);
            System.out.printf("  %-22s %-10s → %d kbps%n", c[2], c[1], r.bitrate);
        }

        System.out.println("\n  Key insight: Client decides when to switch bitrate mid-song.");
        System.out.println("  Server just provides the initial recommendation.");
    }

    // ──────────────────────────────────────────────────────────────────
    // DEMO 3: CDN caching (hits, misses, LRU eviction)
    // ──────────────────────────────────────────────────────────────────
    private static void demo3_CDNCaching(StreamingService streaming, CDNCache cdn) {
        System.out.println("\n" + "─".repeat(70));
        System.out.println("DEMO 3: CDN Caching (Edge Cache Behavior)");
        System.out.println("─".repeat(70));
        System.out.println("Concept: Popular songs cached at edge (~10ms). Long-tail from S3 (~100ms).\n");

        String[] songIds = {"s_1", "s_2", "s_3", "s_4", "s_5", "s_6", "s_7", "s_8", "s_9", "s_10", "s_11", "s_12"};
        System.out.println("Streaming 12 songs through CDN (cache size = 10):");
        for (String id : songIds) {
            StreamingService.StreamResponse r = streaming.playSong(id, "u_1", "high");
            if (r.song != null) {
                String result = streaming.fetchChunkFromCDN(r.song.getS3Path(r.bitrate));
                System.out.println("  " + id + ": " + result);
            }
        }

        System.out.println("\nNow re-request s_1 (was it evicted by LRU?):");
        StreamingService.StreamResponse r = streaming.playSong("s_1", "u_1", "high");
        System.out.println("  " + streaming.fetchChunkFromCDN(r.song.getS3Path(r.bitrate)));

        System.out.println("\n  " + cdn);
    }

    // ──────────────────────────────────────────────────────────────────
    // DEMO 4: Search (text relevance + popularity ranking)
    // ──────────────────────────────────────────────────────────────────
    private static void demo4_Search(SearchService search) {
        System.out.println("\n" + "─".repeat(70));
        System.out.println("DEMO 4: Search (Relevance + Popularity Ranking)");
        System.out.println("─".repeat(70));
        System.out.println("Concept: Elasticsearch multi_match with field weights + popularity boost.\n");

        String[] queries = {"bohemian", "queen", "shape of you", "love"};
        for (String q : queries) {
            List<Song> results = search.search(q, 3);
            System.out.println("  Query: \"" + q + "\"");
            if (results.isEmpty()) {
                System.out.println("    (no results)");
            } else {
                for (Song s : results) {
                    System.out.println("    → " + s);
                }
            }
            System.out.println();
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // DEMO 5: Fuzzy search (typo tolerance)
    // ──────────────────────────────────────────────────────────────────
    private static void demo5_FuzzySearch(SearchService search) {
        System.out.println("─".repeat(70));
        System.out.println("DEMO 5: Fuzzy Search (Typo Tolerance)");
        System.out.println("─".repeat(70));
        System.out.println("Concept: Elasticsearch fuzziness=AUTO allows 1-2 char edits.\n");

        String[] typos = {"bohemain", "quuen", "shaep of yu", "beatlse"};
        for (String q : typos) {
            List<Song> results = search.search(q, 2);
            System.out.println("  Typo query: \"" + q + "\"");
            if (results.isEmpty()) {
                System.out.println("    (no results — edit distance too large)");
            } else {
                for (Song s : results) {
                    System.out.println("    → " + s);
                }
            }
            System.out.println();
        }

        System.out.println("  Autocomplete for \"boh\":");
        List<String> suggestions = search.autocomplete("boh", 3);
        for (String s : suggestions) {
            System.out.println("    → " + s);
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // DEMO 6: Playlist CRUD (many-to-many with ordering)
    // ──────────────────────────────────────────────────────────────────
    private static void demo6_PlaylistCRUD(PlaylistService playlists, MusicMetadataDB db) {
        System.out.println("\n" + "─".repeat(70));
        System.out.println("DEMO 6: Playlist CRUD (Many-to-Many with Position)");
        System.out.println("─".repeat(70));
        System.out.println("Concept: playlist_songs is a join table with a position column.");
        System.out.println("Playlist ↔ Songs is many-to-many with ordering payload.\n");

        Playlist p = playlists.createPlaylist("pl_1", "u_1", "Road Trip Mix", false);
        System.out.println("Created: " + p);

        playlists.addSong("pl_1", "s_1", 0, 1, "idem_1");
        playlists.addSong("pl_1", "s_9", 1, 2, "idem_2");
        playlists.addSong("pl_1", "s_7", 2, 3, "idem_3");
        playlists.addSong("pl_1", "s_4", 1, 4, "idem_4");

        p = playlists.getPlaylist("pl_1");
        System.out.println("\nAfter adding 4 songs (s_4 inserted at position 1):");
        for (Playlist.PlaylistEntry e : p.getSongs()) {
            Song s = db.getSong(e.getSongId());
            System.out.printf("  [%d] %s%n", e.getPosition(), s != null ? s : e.getSongId());
        }

        System.out.println("\nReorder: move s_7 to position 0");
        playlists.reorderSong("pl_1", "s_7", 0, p.getVersion());
        p = playlists.getPlaylist("pl_1");
        for (Playlist.PlaylistEntry e : p.getSongs()) {
            Song s = db.getSong(e.getSongId());
            System.out.printf("  [%d] %s%n", e.getPosition(), s != null ? s : e.getSongId());
        }

        System.out.println("\nIdempotency test: re-add s_1 with same key 'idem_1'");
        PlaylistService.EditResult r = playlists.addSong("pl_1", "s_1", 0, p.getVersion(), "idem_1");
        System.out.println("  Result: " + r);
        System.out.println("  Song count still: " + playlists.getPlaylist("pl_1").getSongs().size());
    }

    // ──────────────────────────────────────────────────────────────────
    // DEMO 7: Collaborative playlist conflict (optimistic locking)
    // ──────────────────────────────────────────────────────────────────
    private static void demo7_CollaborativePlaylistConflict(PlaylistService playlists) {
        System.out.println("\n" + "─".repeat(70));
        System.out.println("DEMO 7: Collaborative Playlist — Optimistic Locking Conflict");
        System.out.println("─".repeat(70));
        System.out.println("Concept: Two users edit same playlist. Version mismatch → 409 Conflict.");
        System.out.println("Low-contention scenario → optimistic locking beats pessimistic.\n");

        Playlist collab = playlists.createPlaylist("pl_collab", "u_1", "Party Mix", true);
        playlists.addSong("pl_collab", "s_1", 0, 1, "c_1");
        collab = playlists.getPlaylist("pl_collab");
        int versionBeforeBothRead = collab.getVersion();

        System.out.println("Both Alice and Bob read playlist at version " + versionBeforeBothRead);

        System.out.println("\nAlice adds s_9 (reads v" + versionBeforeBothRead + "):");
        PlaylistService.EditResult aliceResult = playlists.addSong("pl_collab", "s_9", 1,
                versionBeforeBothRead, "alice_add");
        System.out.println("  Alice: " + aliceResult);

        System.out.println("\nBob tries to add s_7 (still using v" + versionBeforeBothRead + "):");
        PlaylistService.EditResult bobResult = playlists.addSong("pl_collab", "s_7", 1,
                versionBeforeBothRead, "bob_add");
        System.out.println("  Bob: " + bobResult);

        collab = playlists.getPlaylist("pl_collab");
        System.out.println("\nBob retries with current version (v" + collab.getVersion() + "):");
        PlaylistService.EditResult bobRetry = playlists.addSong("pl_collab", "s_7", 2,
                collab.getVersion(), "bob_add_retry");
        System.out.println("  Bob retry: " + bobRetry);

        collab = playlists.getPlaylist("pl_collab");
        System.out.println("\nFinal playlist state: " + collab);
    }

    // ──────────────────────────────────────────────────────────────────
    // DEMO 8: Play history + recommendations (collaborative filtering)
    // ──────────────────────────────────────────────────────────────────
    private static void demo8_PlayHistoryAndRecommendations(StreamingService streaming,
            PlayHistoryStore playHistory, RecommendationService reco, MusicMetadataDB db) {
        System.out.println("\n" + "─".repeat(70));
        System.out.println("DEMO 8: Play History → Kafka → Recommendations");
        System.out.println("─".repeat(70));
        System.out.println("Concept: Play events → Kafka → Cassandra. Offline Spark pipeline");
        System.out.println("reads history → collaborative filtering → precomputed playlists.\n");

        System.out.println("Simulating listening sessions (>30s = counts as play):");

        // Alice listens to rock
        streaming.recordPlay("u_1", "s_1", 240000, 320); // Bohemian Rhapsody
        streaming.recordPlay("u_1", "s_2", 120000, 320); // We Will Rock You
        streaming.recordPlay("u_1", "s_4", 250000, 320); // Come Together
        streaming.recordPlay("u_1", "s_5", 180000, 320); // Here Comes the Sun

        // Bob listens to rock + some pop (overlaps with Alice)
        streaming.recordPlay("u_2", "s_1", 354000, 128); // Bohemian Rhapsody
        streaming.recordPlay("u_2", "s_4", 259000, 128); // Come Together
        streaming.recordPlay("u_2", "s_7", 228000, 128); // Rolling in the Deep
        streaming.recordPlay("u_2", "s_9", 233000, 128); // Shape of You
        streaming.recordPlay("u_2", "s_10", 219000, 128); // Shake It Off

        // Charlie listens to pop + some rock overlap with Bob
        streaming.recordPlay("u_3", "s_7", 228000, 320); // Rolling in the Deep
        streaming.recordPlay("u_3", "s_9", 233000, 320); // Shape of You
        streaming.recordPlay("u_3", "s_8", 285000, 320); // Someone Like You
        streaming.recordPlay("u_3", "s_11", 231000, 320); // Blank Space

        // One skip (< 30s, should NOT count)
        streaming.recordPlay("u_1", "s_10", 5000, 320);  // Shake It Off — skipped

        System.out.println("  Alice: Bohemian Rhapsody, We Will Rock You, Come Together, Here Comes the Sun");
        System.out.println("  Bob:   Bohemian Rhapsody, Come Together, Rolling in Deep, Shape of You, Shake It Off");
        System.out.println("  Charlie: Rolling in Deep, Shape of You, Someone Like You, Blank Space");
        System.out.println("  Alice also skipped Shake It Off (<30s, doesn't count)");

        System.out.println("\nAlice's recently played:");
        for (var event : playHistory.getRecentPlays("u_1", 5)) {
            Song s = db.getSong(event.getSongId());
            System.out.printf("  %s — %s (counts=%s)%n",
                    s != null ? s.getTitle() : event.getSongId(),
                    event.getPlayedAt(), event.countsAsPlay());
        }

        Map<String, Long> counts = playHistory.aggregatePlayCounts();
        System.out.println("\nBatch play count aggregation (simulating Spark job):");
        counts.forEach((songId, count) -> {
            Song s = db.getSong(songId);
            System.out.printf("  %s: +%d plays%n", s != null ? s.getTitle() : songId, count);
        });

        System.out.println("\nRecommendations for Alice (collaborative filtering):");
        System.out.println("  Alice listens to rock. Bob also listens to rock (overlap: s_1, s_4).");
        System.out.println("  Bob also listens to: Rolling in the Deep, Shape of You, Shake It Off.");
        System.out.println("  → Recommend Bob's non-overlapping songs to Alice:\n");

        reco.runBatchPipeline();
        List<Song> aliceReco = reco.generateDailyMix("u_1", 5);
        for (Song s : aliceReco) {
            System.out.println("    → " + s);
        }

        System.out.println("\n  Total play events in Cassandra: " + playHistory.getTotalEvents());
    }
}
