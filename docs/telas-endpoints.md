# Telas e endpoints

Rotas da API Watchwise agrupadas por tela. Os parâmetros de consulta foram omitidos, salvo quando identificam o fluxo da tela. As telas de detalhe de conteúdo compartilham as rotas comuns indicadas na seção própria.

## Perfil e diário

### Settings

- GET /users/me/settings
- PATCH /users/me
- DELETE /users/me/banner
- GET /users/me/follow-requests
- POST /users/me/follow-requests/{requesterId}/accept
- DELETE /users/me/follow-requests/{requesterId}
- GET /users/me/blocked
- POST /users/me/blocked/{userId}
- DELETE /users/me/blocked/{userId}

### Home

- GET /users/{userId}/summary/home
- POST /diary — marcar o próximo episódio como assistido
- GET /notifications/view — abrir notificações completas
- GET /feed — abrir o feed completo
- GET /contents/{contentId}/page — abrir o detalhe de conteúdo

### Notifications

- GET /notifications/view
- PATCH /notifications/{notificationId}/read
- POST /users/me/follow-requests/{requesterId}/accept
- DELETE /users/me/follow-requests/{requesterId}

### User Progress

- GET /users/{userId}
- GET /users/{userId}/series-in-progress
- POST /diary
- PATCH /diary/{diaryEntryId}
- DELETE /diary/{diaryEntryId}
- DELETE /diary/series/{seriesTmdbId}

### Episode Ratings Map

- GET /users/{userId}
- GET /users/{userId}/episode-ratings-map

### Episode Ratings Map (Show)

- GET /users/{userId}
- GET /users/{userId}/series/{seriesTmdbId}/episode-ratings

### Diary History

- GET /users/{userId}
- GET /users/{userId}/summary/home
- GET /users/{userId}/diary/series
- GET /users/{userId}/diary/daily-summary
- GET /users/{userId}/diary/view?hasReview=false

### Diary Reviews

- GET /users/{userId}
- GET /users/{userId}/summary/home
- GET /users/{userId}/diary/series
- GET /users/{userId}/diary/view?hasReview=true
- GET /users/{userId}/reviews
- GET /diary/{diaryEntryId} — abrir uma review de evento do diário
- GET /reviews/{reviewId} — abrir a review própria de ContentReview

### Review

- GET /diary/{diaryEntryId} ou GET /reviews/{reviewId}, conforme a origem; GET /reviews/{reviewId} consulta somente uma review própria
- GET /users/{userId} — perfil do autor, quando necessário
- GET /diary/{diaryEntryId}/comments
- POST /diary/{diaryEntryId}/comments
- POST /diary/{diaryEntryId}/like
- DELETE /diary/{diaryEntryId}/like
- GET /reviews/{reviewId}/comments
- POST /reviews/{reviewId}/comments
- POST /reviews/{reviewId}/like
- DELETE /reviews/{reviewId}/like
- PATCH /diary/{diaryEntryId}
- DELETE /diary/{diaryEntryId}
- PATCH /reviews/{reviewId}
- DELETE /reviews/{reviewId}

### User Profile

- GET /users/{userId}
- GET /users/{userId}/summary?type=MOVIE|SERIES
- GET /users/{userId}/top5/{type}
- POST /users/{userId}/follow
- DELETE /users/{userId}/follow
- POST /users/me/top5/{type}
- PATCH /users/me/top5/{type}/{top5EntryId}
- DELETE /users/me/top5/{type}/{top5EntryId}

### Month in Review

- GET /users/{userId}
- GET /users/{userId}/summary/month

### Year in Review

- GET /users/{userId}
- GET /users/{userId}/summary/year

### All Time Stats

- GET /users/{userId}
- GET /users/{userId}/summary/all-time
- GET /users/{userId}/summary/all-time/edition

### Watchlist

- GET /users/{userId}/watchlist/view
- POST /users/me/watchlist/{type}
- PATCH /users/me/watchlist/{type}/{watchlistEntryId}
- DELETE /users/me/watchlist/{type}/{watchlistEntryId}

## Busca e listas

### My Lists

- GET /users/me/lists/view
- GET /lists/{listId}/view
- GET /lists/{listId}/progress
- GET /lists/{listId}/comments
- POST /lists/{listId}/comments
- POST /lists/{listId}/like
- DELETE /lists/{listId}/like
- PATCH /lists/{listId}
- DELETE /lists/{listId}

### Community Lists

- GET /lists/discover/view
- GET /users/{userId}/lists/view
- GET /lists/{listId}/view
- GET /lists/{listId}/comments
- POST /lists/{listId}/comments
- POST /lists/{listId}/like
- DELETE /lists/{listId}/like

### Search

- GET /search/view

### Create List

- GET /search
- GET /contents/selection/series/{seriesTmdbId}/seasons
- GET /contents/selection/series/{seriesTmdbId}/seasons/{seasonNumber}/episodes
- POST /users/me/lists
- POST /users/me/lists/bulk
- POST /lists/{listId}/items

### List

- GET /lists/{listId}/view
- GET /lists/{listId}/progress
- GET /lists/{listId}/comments
- POST /lists/{listId}/comments
- POST /lists/{listId}/like
- DELETE /lists/{listId}/like
- PATCH /lists/{listId}
- DELETE /lists/{listId}
- POST /lists/{listId}/items
- POST /lists/{listId}/items/bulk
- PATCH /lists/{listId}/items/{itemId}
- DELETE /lists/{listId}/items/{itemId}

### Episodes List

- GET /lists/{listId}/view?type=EPISODE
- GET /lists/{listId}/comments
- POST /lists/{listId}/comments
- POST /lists/{listId}/like
- DELETE /lists/{listId}/like
- POST /lists/{listId}/items
- PATCH /lists/{listId}/items/{itemId}
- DELETE /lists/{listId}/items/{itemId}

### List of Lists

- GET /lists/{listId}/view
- GET /lists/{childListId}/view
- GET /lists/{listId}/comments
- POST /lists/{listId}/comments
- POST /lists/{listId}/like
- DELETE /lists/{listId}/like

### List Progress

- GET /lists/{listId}/progress
- GET /lists/{listId}/comments
- POST /lists/{listId}/comments
- POST /lists/{listId}/like
- DELETE /lists/{listId}/like

## Picks

### Picks Explore

- GET /picks-templates
- GET /picks-templates/{templateId}

### My Picks

- GET /users/me/picks/view
- GET /users/me/picks-templates/view
- GET /picks/{pickId}/view
- GET /picks-templates/{templateId}

### Create Picks Template

- GET /search
- GET /contents/selection/series/{seriesTmdbId}/seasons
- GET /contents/selection/series/{seriesTmdbId}/seasons/{seasonNumber}/episodes
- POST /picks-templates

### Template Detail

- GET /picks-templates/{templateId}
- GET /picks-templates/{templateId}/my-picks/view
- GET /picks-templates/{templateId}/picks/view
- GET /picks-templates/{templateId}/comments
- POST /picks-templates/{templateId}/comments
- POST /picks-templates/{templateId}/like
- DELETE /picks-templates/{templateId}/like

### Answer Template

- GET /picks-templates/{templateId}
- GET /picks-templates/{templateId}/categories/{categoryId}/options/view
- GET /search
- GET /contents/selection/series/{seriesTmdbId}/seasons
- GET /contents/selection/series/{seriesTmdbId}/seasons/{seasonNumber}/episodes
- POST /picks-templates/{templateId}/picks

### Pick Detail

- GET /picks/{pickId}/view
- GET /picks/{pickId}/comments
- POST /picks/{pickId}/comments
- POST /picks/{pickId}/like
- DELETE /picks/{pickId}/like

## Calendário, social e pessoas

### Calendar

- GET /users/me/calendar

### TMDB Trending

- GET /trending
- GET /trending/{type}

### Social/Feed

- GET /feed
- GET /diary/{diaryEntryId}/comments
- POST /diary/{diaryEntryId}/comments
- POST /diary/{diaryEntryId}/like
- DELETE /diary/{diaryEntryId}/like
- GET /reviews/{reviewId}/comments
- POST /reviews/{reviewId}/comments
- POST /reviews/{reviewId}/like
- DELETE /reviews/{reviewId}/like
- GET /dropped/{droppedEntryId}/comments
- POST /dropped/{droppedEntryId}/comments
- POST /dropped/{droppedEntryId}/like
- DELETE /dropped/{droppedEntryId}/like
- GET /picks/{pickId}/comments
- POST /picks/{pickId}/comments
- POST /picks/{pickId}/like
- DELETE /picks/{pickId}/like
- GET /picks-templates/{templateId}/comments
- POST /picks-templates/{templateId}/comments
- POST /picks-templates/{templateId}/like
- DELETE /picks-templates/{templateId}/like

### Person

- GET /people/{personTmdbId}/view
- POST /users/me/follow-people/{personTmdbId}
- DELETE /users/me/follow-people/{personTmdbId}

## Content Detail

### Rotas comuns — Movie, Series, Season e Episode

- POST /contents/reference — somente se ainda não houver contentId
- GET /contents/{contentId}/page
- GET /contents/{contentId}/reviews
- GET /contents/{contentId}/comments
- POST /contents/{contentId}/comments
- GET /diary/{diaryEntryId}/comments
- POST /diary/{diaryEntryId}/comments
- POST /diary/{diaryEntryId}/like
- DELETE /diary/{diaryEntryId}/like
- GET /reviews/{reviewId}/comments
- POST /reviews/{reviewId}/comments
- POST /reviews/{reviewId}/like
- DELETE /reviews/{reviewId}/like
- GET /dropped/{droppedEntryId}/comments
- POST /dropped/{droppedEntryId}/comments
- POST /dropped/{droppedEntryId}/like
- DELETE /dropped/{droppedEntryId}/like
- POST /comments/{commentId}/like
- DELETE /comments/{commentId}/like
- DELETE /comments/{commentId}

### Movie

- POST /diary
- PATCH /diary/{diaryEntryId}
- DELETE /diary/{diaryEntryId}
- GET /users/{userId}/lists
- POST /lists/{listId}/items
- DELETE /lists/{listId}/items/{itemId}
- POST /users/me/watchlist/MOVIE
- DELETE /users/me/watchlist/MOVIE/{watchlistEntryId}
- POST /users/me/dropped/MOVIE/{tmdbId}
- DELETE /users/me/dropped/MOVIE/{tmdbId}

### Series

- POST /diary/bulk
- PATCH /diary/{diaryEntryId}
- DELETE /diary/{diaryEntryId}
- DELETE /diary/series/{seriesTmdbId}
- PUT /contents/{contentId}/my-rating
- DELETE /contents/{contentId}/my-rating
- POST /contents/{contentId}/reviews
- GET /users/{userId}/lists
- POST /lists/{listId}/items
- DELETE /lists/{listId}/items/{itemId}
- POST /users/me/watchlist/SERIES
- DELETE /users/me/watchlist/SERIES/{watchlistEntryId}
- POST /users/me/dropped/SERIES/{tmdbId}
- DELETE /users/me/dropped/SERIES/{tmdbId}

### Season

- POST /diary/bulk
- PUT /contents/{contentId}/my-rating
- DELETE /contents/{contentId}/my-rating
- POST /contents/{contentId}/reviews
- GET /users/{userId}/lists
- POST /lists/{listId}/items
- DELETE /lists/{listId}/items/{itemId}

### Episode

- POST /diary
- PATCH /diary/{diaryEntryId}
- DELETE /diary/{diaryEntryId}
- GET /users/{userId}/lists
- POST /lists/{listId}/items
- DELETE /lists/{listId}/items/{itemId}

## Games

### Games

- GET /games/today/view
- GET /games/{gameType}/today

### Games Ranking

- GET /games/rankings/view

### Movie by Poster

- GET /games/{gameType}/play — gameType=MOVIE_BY_POSTER
- GET /games/{gameType}/search?view=true
- GET /games/play/media/{grantToken}
- POST /games/{gameType}/play/attempt
- POST /games/{gameType}/play/give-up

### Series by Poster

- GET /games/{gameType}/play — gameType=SERIES_BY_POSTER
- GET /games/{gameType}/search?view=true
- GET /games/play/media/{grantToken}
- POST /games/{gameType}/play/attempt
- POST /games/{gameType}/play/give-up

### Person by Face

- GET /games/{gameType}/play — gameType=PERSON_BY_FACE
- GET /games/{gameType}/search?view=true
- GET /games/play/media/{grantToken}
- POST /games/{gameType}/play/attempt
- POST /games/{gameType}/play/give-up

### Episode by Frame

- GET /games/{gameType}/play — gameType=EPISODE_BY_FRAME
- GET /games/EPISODE_BY_FRAME/search/series
- GET /games/EPISODE_BY_FRAME/series/{seriesTmdbId}/seasons
- GET /games/EPISODE_BY_FRAME/series/{seriesTmdbId}/seasons/{seasonNumber}/episodes
- GET /games/play/media/{grantToken}
- POST /games/{gameType}/play/attempt
- POST /games/{gameType}/play/give-up

### Movie by Info

- GET /games/{gameType}/play — gameType=MOVIE_BY_INFO
- GET /games/{gameType}/search
- POST /games/{gameType}/play/attempt
- POST /games/{gameType}/play/give-up

### Series by Info

- GET /games/{gameType}/play — gameType=SERIES_BY_INFO
- GET /games/{gameType}/search
- POST /games/{gameType}/play/attempt
- POST /games/{gameType}/play/give-up

### Actor by Movie Filmography

- GET /games/{gameType}/play — gameType=ACTOR_BY_MOVIE_FILMOGRAPHY
- GET /games/{gameType}/search
- POST /games/{gameType}/play/attempt
- POST /games/{gameType}/play/give-up

### Actor by Series Filmography

- GET /games/{gameType}/play — gameType=ACTOR_BY_SERIES_FILMOGRAPHY
- GET /games/{gameType}/search
- POST /games/{gameType}/play/attempt
- POST /games/{gameType}/play/give-up
