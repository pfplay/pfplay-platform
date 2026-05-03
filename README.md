# PFPlay Backend

> **PFP Playground for Music** - Real-time music party room platform

A sophisticated Spring Boot backend service powering PFPlay, a real-time collaborative music party platform where users can create virtual party rooms, share music, DJ together, and interact in real-time.

[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.java.net/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2.3-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![MySQL](https://img.shields.io/badge/MySQL-8.0.30-blue.svg)](https://www.mysql.com/)
[![Redis](https://img.shields.io/badge/Redis-Latest-red.svg)](https://redis.io/)

## Table of Contents

- [Features](#features)
- [Technology Stack](#technology-stack)
- [Architecture](#architecture)
- [Getting Started](#getting-started)
- [API Documentation](#api-documentation)
- [WebSocket Events](#websocket-events)
- [Project Structure](#project-structure)
- [Configuration](#configuration)
- [Resources](#resources)

## Features

### Party Room System
- **Real-time Music Party Rooms**: Create and manage virtual party rooms with live music playback
- **DJ Queue System**: Automated DJ rotation with playlist-based music sharing
- **Live Playback Synchronization**: Real-time music playback coordination across all users
- **Room Management**: Configure stage types, playback limits, notices, and room settings

### User Management
- **Dual User System**:
  - Members (OAuth-authenticated users)
  - Guests (temporary anonymous access)
- **Authority Tiers**: Flexible user privilege system (FM, AM, GT)
- **Profile & Avatar System**: Customizable user profiles with avatar resources
- **Activity Tracking**: Track DJ points and various user activities

### Crew & Moderation
- **Hierarchical Roles**: HOST, COMMUNITY_MANAGER, MODERATOR, CLUBBER, LISTENER
- **Comprehensive Moderation Tools**:
  - Grade management (promote/demote)
  - Chat penalties and bans
  - User expulsion and blocking
  - Message removal
- **Crew History Tracking**: Complete audit trail for all moderation actions

### Real-time Communication
- **WebSocket Integration**: STOMP-based real-time messaging
- **Group Chat**: Per-room chat with Redis pub/sub distribution
- **Live Event Broadcasting**: 15+ event types for real-time updates
  - Playback events (start, skip, reactions)
  - User actions (join, leave, grade changes)
  - Room updates (notice changes, deactivation)

### Playlist & Music Management
- **User Playlists**: Create, organize, and manage personal playlists
- **YouTube Integration**: Search and add music via YouTube API
- **Track Metadata**: Store and manage track information
- **Grab Feature**: Save tracks from live playback to personal playlists

### Reaction System
- **Real-time Reactions**: LIKE, DISLIKE, GRAB with live aggregation
- **Motion Animations**: Broadcast reaction motions to all users
- **Reaction History**: Track all user reactions for analytics

### Authentication & Security
- **OAuth2 Integration**: Google and Twitter social login
- **JWT-based Authentication**: Secure cookie-based token management
- **Role-based Access Control**: Method-level security with Spring Security
- **WebSocket Security**: JWT validation in handshake

## Technology Stack

### Core Framework
- **Java 21** - Latest Java LTS version
- **Spring Boot 3.2.3** - Application framework
- **Gradle** - Build automation

### Spring Ecosystem
- Spring Boot Web - RESTful API
- Spring Security - Authentication & authorization
- Spring Data JPA - Data persistence
- Spring Data Redis - Caching & pub/sub
- Spring WebSocket - Real-time communication
- Spring WebFlux - Reactive HTTP clients
- Spring OAuth2 - Social login integration
- Spring Cache - Caching abstraction

### Database & Persistence
- **MySQL 8.0.30** - Primary relational database
- **Redis** - Caching, pub/sub messaging, distributed locks
- **JPA/Hibernate** - ORM framework
- **QueryDSL 5.0.0** - Type-safe queries
- **P6Spy** - SQL logging and monitoring

### Security
- **JWT (JJWT 0.12.3)** - JSON Web Token
- **Spring Security** - Security framework
- **OAuth2 Client & Resource Server** - Social authentication
- **Passay** - Password validation

### API & Documentation
- **SpringDoc OpenAPI 3** - API documentation (Swagger UI)
- **Lombok** - Boilerplate reduction

### External Integrations
- **Google YouTube API v3** - Music search and metadata
- **Google OAuth2** - Social login
- **Twitter OAuth2** - Social login
- **PyTube Service** - Custom YouTube streaming service

### Development & DevOps
- Spring Boot DevTools - Hot reload
- Docker - Containerization
- GitHub Actions - CI/CD

## Architecture

### Design Pattern
The project follows **Hexagonal Architecture (Ports & Adapters)** with DDD principles:

```
┌─────────────────────────────────────────────────────┐
│  Inbound Adapters                                   │
│  adapter/in/web/      (REST Controllers)            │
│  adapter/in/listener/ (Redis Topic Listeners)       │
│  adapter/in/stomp/    (WebSocket STOMP Controllers)  │
└──────────────────────┬──────────────────────────────┘
                       ↓
┌──────────────────────┴──────────────────────────────┐
│  Application Layer                                   │
│  application/service/    (Use Case Orchestration)    │
│  application/port/out/   (Outbound Port Interfaces)  │
└──────────────────────┬──────────────────────────────┘
                       ↓
┌──────────────────────┴──────────────────────────────┐
│  Domain Layer                                        │
│  domain/entity/data/  (JPA Entities + Business Logic)│
│  domain/service/      (Domain Services)              │
│  domain/value/        (Value Objects)                │
└──────────────────────┬──────────────────────────────┘
                       ↑
┌──────────────────────┴──────────────────────────────┐
│  Outbound Adapters                                   │
│  adapter/out/persistence/ (JPA Repositories, QueryDSL)│
│  adapter/out/external/    (Cross-domain Port Adapters)│
└─────────────────────────────────────────────────────┘
```

### Key Architectural Patterns

**1. Hexagonal Architecture (Ports & Adapters)**
- Clear domain boundaries (Party, User, Playlist, etc.)
- Unified data entities (`*Data.java`) with business logic
- Value objects for type safety (UserId, PartyroomId)
- Cross-domain dependencies resolved via Port/Adapter interfaces

**2. Event-Driven Architecture**
- Redis pub/sub for cross-instance communication
- 15+ event types for different domain events
- Decoupled components via message passing
- Scalable distributed architecture

**3. Gradle Multi-Module (6 modules)**
- `common` — Shared Kernel + infrastructure config (JPA, Redis, JWT, Security)
- `realtime` — WebSocket infrastructure (zero domain imports, port interfaces only)
- `playlist` — Playlist domain (depends: `common`)
- `avatar` — Avatar catalog domain (depends: `common`)
- `user` — User domain (depends: `common`, `avatar`)
- `app` — Auth, Party, Admin, Bootstrap (depends: all modules)
- Dependency direction: `app → user → avatar → common`, `app → playlist → common`, `app → realtime → common`

**4. Distributed Systems**
- Redis-based distributed locks
- Session management across instances
- Pub/sub for cross-instance events
- Stateless API design

## Getting Started

### Prerequisites

- **Java 21** or higher
- **Docker & Docker Compose** ([Install link](https://docs.docker.com/engine/install/))
- **Git**

### Installation

1. **Clone the repository**
```bash
git clone https://github.com/pfplay/pfplay-platform.git
cd pfplay-platform
```

2. **Start infrastructure services (MySQL, Redis)**
```bash
docker-compose up -d
```

3. **Configure application properties**

Create `app/src/main/resources/application-local.yml`:
```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/pfplay
    username: your_username
    password: your_password
  data:
    redis:
      host: localhost
      port: 6379

oauth:
  google:
    client-id: your_google_client_id
    client-secret: your_google_client_secret
  twitter:
    client-id: your_twitter_client_id
    client-secret: your_twitter_client_secret

jwt:
  issuer: pfplay
  access-token-secret: your_access_token_secret
  refresh-token-secret: your_refresh_token_secret
```

4. **Build and run the application**
```bash
./gradlew :app:build
./gradlew :app:bootRun --args='--spring.profiles.active=local'
```

The application will start on `http://localhost:8080`

### Docker Deployment

**Stop services**
```bash
docker-compose down
```

**View logs**
```bash
docker-compose logs -f
```

### Verify Installation

**Check health endpoint**
```bash
curl http://localhost:8080/actuator/health
```

**Access API documentation**
```
http://localhost:8080/spec/api
```

## API Documentation

### Interactive API Documentation

When running the application, access the Swagger UI at:
```
http://localhost:8080/spec/api
```

### Main API Endpoints

#### Authentication
- `POST /api/v1/auth/oauth/url` - Generate OAuth authorization URL
- `POST /api/v1/auth/oauth/callback` - Handle OAuth callback
- `POST /api/v1/auth/logout` - User logout

#### Users & Profile
- `GET /api/v1/users/members/sign` - Member OAuth sign-in
- `GET /api/v1/users/guests/sign/**` - Guest sign-in
- `GET /api/v1/users/{userId}` - Get user info
- `PUT /api/v1/users/{userId}/profile` - Update profile
- `PUT /api/v1/users/{userId}/avatar` - Update avatar

#### Party Rooms
- `POST /api/v1/partyrooms` - Create party room
- `GET /api/v1/partyrooms/{partyroomId}` - Get party room info
- `PUT /api/v1/partyrooms/{partyroomId}` - Update party room
- `DELETE /api/v1/partyrooms/{partyroomId}` - Delete party room
- `POST /api/v1/partyrooms/{partyroomId}/access` - Join party room
- `POST /api/v1/partyrooms/{partyroomId}/exit` - Leave party room
- `PUT /api/v1/partyrooms/{partyroomId}/notice` - Update notice
- `POST /api/v1/partyrooms/{partyroomId}/playback/start` - Start playback
- `POST /api/v1/partyrooms/{partyroomId}/playback/skip` - Skip track
- `POST /api/v1/partyrooms/{partyroomId}/playback/reaction` - Send reaction

#### DJ & Queue Management
- `POST /api/v1/partyrooms/{partyroomId}/dj-queue/enqueue` - Join DJ queue
- `POST /api/v1/partyrooms/{partyroomId}/dj-queue/dequeue` - Leave DJ queue
- `PUT /api/v1/partyrooms/{partyroomId}/dj-queue` - Update DJ queue status

#### Crew Management
- `GET /api/v1/crews/{crewId}` - Get crew info
- `PUT /api/v1/partyrooms/{partyroomId}/crews/{crewId}/grade` - Update crew grade
- `POST /api/v1/partyrooms/{partyroomId}/crews/{crewId}/penalty` - Apply penalty
- `POST /api/v1/partyrooms/{partyroomId}/crews/{crewId}/block` - Block crew

#### Playlists
- `POST /api/v1/playlists` - Create playlist (ROLE_MEMBER required)
- `GET /api/v1/playlists` - Query playlists
- `PATCH /api/v1/playlists/{playlistId}` - Rename playlist
- `DELETE /api/v1/playlists` - Delete playlists
- `POST /api/v1/playlists/{playlistId}/tracks` - Add track
- `DELETE /api/v1/playlists/{playlistId}/tracks` - Remove tracks

#### Music Search
- `GET /api/v1/music-search` - Search music via YouTube

### Authentication

All protected endpoints require JWT authentication via cookies:
- `access_token` - Access token (24h expiration)
- `refresh_token` - Refresh token (7 days expiration)

Cookies are automatically set after successful OAuth login.

## WebSocket Events

### Connection

**Endpoint:** `ws://localhost:8080/ws`

**Authentication:** Include JWT in handshake headers

### Publishing Topics

- `/pub/groups/{chatroomId}/send` - Send group message
- `/pub/heartbeat` - Send heartbeat

### Subscription Topics

Subscribe to receive real-time events:

- `/sub/groups/{chatroomId}` - Group chat messages
- `/sub/events/{partyroomId}/chat-message` - Chat messages
- `/sub/events/{partyroomId}/partyroom-access` - User join/leave events
- `/sub/events/{partyroomId}/playback-start` - Playback start events
- `/sub/events/{partyroomId}/playback-skip` - Playback skip events
- `/sub/events/{partyroomId}/playback-reaction` - Reaction events
- `/sub/events/{partyroomId}/playback-reaction-motion` - Reaction animations
- `/sub/events/{partyroomId}/crew-grade` - Crew grade changes
- `/sub/events/{partyroomId}/crew-penalty` - Crew penalties
- `/sub/events/{partyroomId}/profile-update` - Profile updates
- `/sub/events/{partyroomId}/notice-update` - Notice updates
- `/sub/events/{partyroomId}/partyroom-deactivation` - Room deactivation

### Example: Sending a Chat Message

```javascript
stompClient.send('/pub/groups/' + chatroomId + '/send', {},
  JSON.stringify({ content: 'Hello, world!' })
);
```

### Example: Subscribing to Events

```javascript
stompClient.subscribe('/sub/events/' + partyroomId + '/playback-start',
  (message) => {
    const event = JSON.parse(message.body);
    console.log('Playback started:', event);
  }
);
```

## Project Structure

```
pfplay-platform/
├── common/                             # Shared Kernel + infrastructure config
│   └── src/main/java/com/pfplaybackend/api/common/
│       ├── config/                     # Configuration (Security, Redis, JPA, Cache, Swagger)
│       ├── domain/value/               # Shared Value Objects (UserId, Duration)
│       ├── entity/                     # Base entities
│       ├── enums/                      # Common enums
│       └── exception/                  # Exception handling (GlobalExceptionHandler)
├── realtime/                           # WebSocket infrastructure (zero domain imports)
│   └── src/main/java/com/pfplaybackend/realtime/
│       ├── config/                     # WebSocket/STOMP configuration
│       ├── controller/                 # Heartbeat controller
│       ├── event/                      # Connection/Subscription event listeners
│       ├── interceptor/                # WebSocket handshake interceptor
│       ├── port/                       # Port interfaces (WebSocketAuthPort, SessionCachePort)
│       └── sender/                     # SimpMessageSender
├── playlist/                           # Playlist domain
│   └── src/main/java/com/pfplaybackend/api/playlist/
│       ├── adapter/                    # REST controllers, JPA repositories
│       ├── application/                # Use cases, DTOs
│       └── domain/                     # Entities, services, value objects
├── avatar/                             # Avatar catalog domain (body/face resources, lifecycle)
│   └── src/main/java/com/pfplaybackend/api/avatar/
│       ├── adapter/                    # JPA repositories
│       ├── application/                # AvatarCatalogQueryUseCase port + service, DTOs
│       └── domain/                     # Entities, VOs (AvatarBodyUri/FaceUri/IconUri), enums
├── user/                               # User domain (member, guest, profile)
│   └── src/main/java/com/pfplaybackend/api/user/
│       ├── adapter/                    # REST controllers, JPA repositories
│       ├── application/                # Use cases, ports, DTOs
│       └── domain/                     # Entities, services, value objects
├── app/                                # Auth, Party, Admin, Bootstrap (composition root)
│   └── src/main/java/com/pfplaybackend/api/
│       ├── auth/                       # Authentication & OAuth
│       ├── party/                      # Party room domain (rooms, crew, DJ, playback, chat)
│       ├── admin/                      # Administrative tools
│       └── bootstrap/                  # Cross-module adapters
├── build.gradle                        # Root Gradle config
├── settings.gradle                     # Multi-module settings (6 modules)
├── docs/                               # Design documents (ADRs, context map, naming)
├── Dockerfile                          # Container image
└── README.md                           # This file
```

### Domain Module Structure

Each domain module follows a hexagonal architecture:

```
{domain}/
├── adapter/
│   ├── in/
│   │   ├── web/              # REST Controllers + payload/
│   │   ├── listener/         # Redis Topic Listeners
│   │   └── stomp/            # WebSocket STOMP Controllers
│   └── out/
│       ├── persistence/      # JPA Repositories + QueryDSL (custom/, impl/)
│       └── external/         # Cross-domain Port Adapters
├── application/
│   ├── service/              # Application Services (use cases)
│   ├── port/out/             # Outbound Port Interfaces
│   └── dto/                  # Application DTOs
└── domain/
    ├── entity/data/          # JPA Data Entities (*Data.java)
    ├── service/              # Domain Services
    ├── enums/                # Domain Enums
    ├── value/                # Value Objects
    └── exception/            # Domain Exceptions
```

## Configuration

### Profiles

The application supports multiple profiles:

- `local` - Local development
- `dev` - Development environment
- `prod` - Production environment

Activate a profile:
```bash
./gradlew :app:bootRun --args='--spring.profiles.active=local'
```

### Environment Variables

Key configuration properties:

**Database**
- `SPRING_DATASOURCE_URL` - MySQL connection URL
- `SPRING_DATASOURCE_USERNAME` - Database username
- `SPRING_DATASOURCE_PASSWORD` - Database password

**Redis**
- `SPRING_DATA_REDIS_HOST` - Redis host
- `SPRING_DATA_REDIS_PORT` - Redis port

**OAuth**
- `OAUTH_GOOGLE_CLIENT_ID` - Google OAuth client ID
- `OAUTH_GOOGLE_CLIENT_SECRET` - Google OAuth secret
- `OAUTH_TWITTER_CLIENT_ID` - Twitter OAuth client ID
- `OAUTH_TWITTER_CLIENT_SECRET` - Twitter OAuth secret

**JWT**
- `JWT_ISSUER` - JWT issuer
- `JWT_ACCESS_TOKEN_SECRET` - Access token secret
- `JWT_REFRESH_TOKEN_SECRET` - Refresh token secret

**YouTube API**
- `YOUTUBE_API_KEY` - YouTube Data API key

## Resources

### Project Links
- **Notion Page**: [PFPlay Overview](https://www.notion.so/pfplay/PFPlay-PFP-Playground-for-music-05c88a7cd37f43cdb35756c04d922182)
- **KanBan Board**: [Project Management](https://www.notion.so/pfplay/0578d4b85a93408d99d55f8911e552e6?v=88f049beb991436fa4533fe0c8739045)
- **Research Board**: [Backend Research](https://www.notion.so/pfplay/Backend-Research-Board-a878cfabbf8d418884164fe0d14b8434)

### Design Documents
- [Architecture Decision Records](docs/adr/) — 5 ADRs on key architectural choices
- [Context Map](docs/CONTEXT_MAP.md) — Bounded context boundaries and relationships
- [Naming Convention](docs/NAMING_CONVENTION.md) — Project naming rules
- [DDD Maturity Assessment](docs/MATURITY_ASSESSMENT.md) — Domain-driven design maturity evaluation
- [Refactoring Roadmap](docs/REFACTORING_ROADMAP.md) — Future improvement plans

### Reference Documentation
- [Spring Boot Documentation](https://docs.spring.io/spring-boot/docs/current/reference/html/)
- [Spring Security](https://docs.spring.io/spring-security/reference/)
- [Spring WebSocket](https://docs.spring.io/spring-framework/reference/web/websocket.html)
- [Spring Data JPA](https://docs.spring.io/spring-data/jpa/docs/current/reference/html/)
- [QueryDSL](http://querydsl.com/static/querydsl/latest/reference/html/)
- [YouTube API](https://developers.google.com/youtube/v3)

### Docker References
- [Docker MySQL](https://hub.docker.com/_/mysql)
- [Docker OpenJDK](https://hub.docker.com/_/openjdk)
- [Docker OpenJDK ARM64](https://hub.docker.com/r/arm64v8/openjdk)

## License

Copyright (c) PFPlay. All rights reserved.

---

**Built with Spring Boot 3.2.3 | Java 21 | MySQL 8.0.30 | Redis**