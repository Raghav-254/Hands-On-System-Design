# URL Shortener - System Design

Design a system like **bit.ly**: given a long URL, return a short alias; when users open the short link, redirect them to the original URL. Read-heavy (billions of redirects, millions of creates).

## Key concepts

- **Key generation**: Base62 short codes; random + retry, DB sequence, or range-based for scale.
- **Redirect**: Cache-first (Redis); 302 for analytics, 301 for cacheability.
- **Scale**: Read-heavy; cache handles most traffic; DB for persistence.

## Cheatsheet

→ **[INTERVIEW_CHEATSHEET.md](./INTERVIEW_CHEATSHEET.md)** — requirements, API, key generation, data model, architecture, and interview talking points.
