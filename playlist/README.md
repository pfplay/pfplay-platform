# Playlist Module — Music Playlist Context

> 실사 기준: 2026-07-31 (`origin/develop`).

## Bounded Context

음악 플레이리스트와 트랙을 관리하는 도메인 모듈.
사용자의 플레이리스트 CRUD, 트랙 추가/삭제/순서 변경, 음악 검색을 담당한다.

## 책임

- 플레이리스트 생성/수정/삭제 (신규 회원에게 기본 플레이리스트 시드)
- 트랙 추가/삭제/순서 변경, 재생 커서(NOW/NEXT) 노출
- 음악 검색 — **`pfplay-streaming` 사이드카 경유**
- 다른 사용자 트랙 가져오기 (Grab)

> 음악 검색은 YouTube Data API 를 직접 호출하지 않는다. `YoutubeSearchService` 는 인터페이스이고
> 유일한 구현이 `PytubeSearchService`(사이드카 HTTP 호출)다. 그래서 이 레포에는 YouTube API 키가
> 없고, 사이드카가 죽으면 **검색만** 실패한다.

## Aggregate

### Playlist Aggregate
- **Root**: `PlaylistData` — 플레이리스트 생성, 이름 변경
- **내부 엔티티**: `TrackData` — 트랙 정보 (ID 참조로 Playlist에 연결)
- **Aggregate Port**: `PlaylistAggregatePort` — Root를 통한 단일 접근점

## 제공하는 Port 인터페이스

| Port | 위치 | 용도 |
|------|------|------|
| `PlaylistAggregatePort` | `domain/port/` | Playlist Aggregate CRUD (엔티티 레벨) |
| `PlaylistQueryPort` | `application/port/out/` | DTO 기반 조회 (Application 레벨) |

## 소비하는 외부 Port

없음 (독립적 도메인)

## 핵심 엔티티

| 엔티티 | 비즈니스 로직 |
|--------|-------------|
| `PlaylistData` | `create()`, `rename()` |
| `TrackData` | `create()` + 트랙 메타데이터 |

## Application Service

| Service | 역할 |
|---------|------|
| `PlaylistCommandService` | 플레이리스트 생성/수정/삭제 |
| `PlaylistQueryService` | 플레이리스트 목록/상세 조회 |
| `TrackCommandService` | 트랙 추가/삭제/순서 변경 |
| `TrackQueryService` | 트랙 목록 조회 (커서 페이지네이션, NOW/NEXT 커서) |
| `GrabTrackService` | 다른 사용자의 트랙 가져오기 |
| `TempPlaylistService` | Quick-DJ 단발 큐용 임시 플레이리스트 |
| `search/MusicSearchService` | 음악 검색 오케스트레이션 |
| `search/YoutubeSearchService` (interface) → `PytubeSearchService` | 사이드카 검색 어댑터 |

## 의존 방향

```
playlist → common (Shared Kernel)
```

## 외부에서의 접근

- **Party Context**: `PlaylistCommandPort`, `PlaylistQueryPort` 어댑터를 통해 접근
- **User Context**: `PlaylistSetupPort` 어댑터를 통해 회원가입 시 기본 플레이리스트 생성
