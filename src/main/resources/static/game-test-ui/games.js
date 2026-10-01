(function () {
  "use strict";

  const GAME_META = {
    MOVIE_BY_POSTER: { label: "Filme por pôster", href: "movie-by-poster.html", target: "MOVIE", mode: "candidate" },
    SERIES_BY_POSTER: { label: "Série por pôster", href: "series-by-poster.html", target: "SERIES", mode: "candidate" },
    PERSON_BY_FACE: { label: "Pessoa por rosto", href: "person-by-face.html", target: "PERSON", mode: "candidate" },
    EPISODE_BY_FRAME: { label: "Episódio por frame", href: "episode-by-frame.html", target: "EPISODE", mode: "episode" },
    MOVIE_BY_INFO: { label: "Filme por informações", href: "movie-by-info.html", target: "MOVIE", mode: "info" },
    SERIES_BY_INFO: { label: "Série por informações", href: "series-by-info.html", target: "SERIES", mode: "info" },
    ACTOR_BY_MOVIE_FILMOGRAPHY: { label: "Ator por filmografia de filmes", href: "actor-by-movie-filmography.html", target: "PERSON", mode: "filmography" },
    ACTOR_BY_SERIES_FILMOGRAPHY: { label: "Ator por filmografia de séries", href: "actor-by-series-filmography.html", target: "PERSON", mode: "filmography", majorRoles: true }
  };

  const COMPARISON_FIELDS = [
    ["platforms", "Plataformas"],
    ["genres", "Gêneros"],
    ["year", "Ano"],
    ["certification", "Classificação"],
    ["directorOrCreators", "Diretor/criadores"],
    ["cast", "Elenco"],
    ["productionCompanies", "Produtoras"],
    ["revenueOrSeasons", "Receita/temporadas"]
  ];

  const UI_PATH_MARKER = "/game-test-ui/";
  const uiPathIndex = window.location.pathname.indexOf(UI_PATH_MARKER);
  const contextPath = uiPathIndex >= 0 ? window.location.pathname.slice(0, uiPathIndex) : "";

  const state = {
    config: null,
    user: null,
    game: null,
    selectedCandidate: null,
    selectedSeries: null,
    selectedEpisode: null,
    episodeSeasons: [],
    episodeOptions: [],
    majorRoles: true
  };

  function byId(id) {
    return document.getElementById(id);
  }

  function node(tag, options, children) {
    const element = document.createElement(tag);
    const config = options || {};
    if (config.className) element.className = config.className;
    if (config.text !== undefined && config.text !== null) element.textContent = String(config.text);
    if (config.id) element.id = config.id;
    if (config.type) element.type = config.type;
    if (config.value !== undefined) element.value = config.value;
    if (config.href) element.href = config.href;
    if (config.checked !== undefined) element.checked = config.checked;
    if (config.disabled !== undefined) element.disabled = config.disabled;
    if (config.attributes) {
      Object.entries(config.attributes).forEach(function ([key, value]) {
        if (value !== null && value !== undefined) element.setAttribute(key, String(value));
      });
    }
    (children || []).forEach(function (child) {
      if (child) element.append(child);
    });
    return element;
  }

  function clear(element) {
    if (element) element.replaceChildren();
  }

  function setVisible(element, visible) {
    if (element) element.classList.toggle("hidden", !visible);
  }

  function readCookie(name) {
    const prefix = name + "=";
    const item = document.cookie.split(";").map(function (value) { return value.trim(); }).find(function (value) {
      return value.indexOf(prefix) === 0;
    });
    return item ? item.slice(prefix.length) : null;
  }

  function isSafeMediaUrl(value) {
    if (!value || typeof value !== "string") return false;
    if (value.startsWith("//")) return false;
    if (value.startsWith("/")) return true;
    try {
      const parsed = new URL(value, window.location.origin);
      return parsed.protocol === "https:" || parsed.protocol === "http:";
    } catch (error) {
      return false;
    }
  }

  function mediaImage(url, alt, className) {
    if (!isSafeMediaUrl(url)) return null;
    return node("img", {
      className: className || "",
      attributes: { src: url, alt: alt || "Imagem do jogo", loading: "lazy" }
    });
  }

  function formatValue(value) {
    if (value === null || value === undefined || value === "") return "—";
    if (Array.isArray(value)) return value.length ? value.join(", ") : "—";
    if (typeof value === "object") return JSON.stringify(value);
    return String(value);
  }

  function statusLabel(status) {
    return {
      NOT_PLAYED: "Não jogado",
      IN_PROGRESS: "Em andamento",
      COMPLETED: "Concluído",
      FAILED: "Falhou"
    }[status] || status || "Desconhecido";
  }

  function statusBadge(status) {
    return node("span", { className: "status status-" + String(status || "unknown").toLowerCase(), text: statusLabel(status) });
  }

  function displayError(error) {
    if (!error) return "Não foi possível concluir a operação.";
    if (error.status === 401) return "Sessão não autenticada. Volte ao painel e faça login.";
    return error.message || "Não foi possível concluir a operação.";
  }

  async function readResponseBody(response) {
    if (response.status === 204) return null;
    const text = await response.text();
    if (!text) return null;
    const contentType = response.headers.get("content-type") || "";
    if (contentType.includes("json")) {
      try { return JSON.parse(text); } catch (error) { return text; }
    }
    return text;
  }

  function toApiError(response, body) {
    const error = new Error(
      body && typeof body === "object"
        ? (body.message || body.error || body.detail || "Erro da API")
        : "Erro da API (" + response.status + ")"
    );
    error.status = response.status;
    error.path = body && typeof body === "object" ? body.path : null;
    error.body = body;
    return error;
  }

  async function request(path, options) {
    const config = options || {};
    const method = (config.method || "GET").toUpperCase();
    const headers = new Headers(config.headers || {});
    let body = config.body;
    if (body !== undefined && body !== null && typeof body !== "string") {
      body = JSON.stringify(body);
      headers.set("Content-Type", "application/json");
    }
    if (["POST", "PUT", "PATCH", "DELETE"].includes(method)) {
      const csrf = readCookie("XSRF-TOKEN");
      if (csrf) headers.set("X-XSRF-TOKEN", decodeURIComponent(csrf));
    }
    const resolvedPath = path.startsWith("/") ? contextPath + path : path;
    const response = await fetch(resolvedPath, {
      method,
      headers,
      body,
      credentials: "same-origin"
    });
    const responseBody = await readResponseBody(response);
    if (!response.ok) throw toApiError(response, responseBody);
    return responseBody;
  }

  const api = {
    request,
    login: function (identifier, password) {
      return request("/auth/login", { method: "POST", body: { identifier: identifier, password: password } });
    },
    logout: function () { return request("/auth/logout", { method: "POST" }); },
    getCurrentUser: function () { return request("/users/me"); },
    getToday: function () { return request("/games/today"); },
    getGameToday: function (gameType, majorRoles) {
      const suffix = majorRoles === undefined ? "" : "?majorRoles=" + encodeURIComponent(majorRoles);
      return request("/games/" + encodeURIComponent(gameType) + "/today" + suffix);
    },
    search: function (gameType, query) {
      return request("/games/" + encodeURIComponent(gameType) + "/search?q=" + encodeURIComponent(query) + "&page=1&size=20");
    },
    searchEpisodeSeries: function (query) {
      return request("/games/EPISODE_BY_FRAME/search/series?q=" + encodeURIComponent(query) + "&page=1&size=20");
    },
    listEpisodeSeasons: function (seriesTmdbId) {
      return request("/games/EPISODE_BY_FRAME/series/" + encodeURIComponent(seriesTmdbId) + "/seasons");
    },
    listEpisodeEpisodes: function (seriesTmdbId, seasonNumber) {
      return request("/games/EPISODE_BY_FRAME/series/" + encodeURIComponent(seriesTmdbId) + "/seasons/" + encodeURIComponent(seasonNumber) + "/episodes");
    },
    submitAttempt: function (gameType, body, majorRoles) {
      const suffix = majorRoles === undefined ? "" : "?majorRoles=" + encodeURIComponent(majorRoles);
      return request("/games/" + encodeURIComponent(gameType) + "/attempt" + suffix, { method: "POST", body: body });
    },
    giveUp: function (gameType) {
      return request("/games/" + encodeURIComponent(gameType) + "/give-up", { method: "POST" });
    },
    getHistory: function (page) { return request("/games/history?page=" + encodeURIComponent(page || 1) + "&size=5"); },
    getGeneralRanking: function (page) { return request("/games/rankings/general?page=" + encodeURIComponent(page || 1) + "&size=5"); }
  };

  function showError(message, targetId) {
    const target = byId(targetId || "game-error") || byId("page-error");
    if (!target) return;
    target.textContent = message;
    target.classList.add("visible");
  }

  function clearError(targetId) {
    const target = byId(targetId || "game-error") || byId("page-error");
    if (!target) return;
    target.textContent = "";
    target.classList.remove("visible");
  }

  function showSession(user) {
    state.user = user;
    const username = byId("session-username");
    if (username) username.textContent = user && user.username ? user.username : "usuário autenticado";
    setVisible(byId("session-panel"), true);
    setVisible(byId("login-panel"), false);
    setVisible(byId("unauthenticated-panel"), false);
  }

  function showLoggedOut() {
    state.user = null;
    setVisible(byId("session-panel"), false);
    setVisible(byId("login-panel"), true);
    setVisible(byId("unauthenticated-panel"), true);
  }

  function renderStats(game) {
    const attempts = byId("attempts");
    const remaining = byId("attempts-remaining");
    const score = byId("score");
    if (attempts) attempts.textContent = String(game.attemptsUsed || 0) + " / " + String(game.maxAttempts || 0);
    if (remaining) remaining.textContent = String(game.attemptsRemaining || 0);
    if (score) score.textContent = String(game.score || 0);
  }

  function renderImages(game) {
    const stage = byId("game-image");
    const grid = byId("visible-images");
    if (stage) {
      clear(stage);
      const image = mediaImage(game.imageUrl, "Imagem do desafio");
      stage.append(image || node("div", { className: "image-placeholder", text: "A imagem não é exibida nesta modalidade enquanto o jogo está aberto." }));
    }
    if (grid) {
      clear(grid);
      const imageUrls = game.status === "COMPLETED" && game.imageUrls && game.imageUrls.length
        ? game.imageUrls
        : (game.visibleImageUrls || []);
      imageUrls.forEach(function (url, index) {
        const image = mediaImage(url, "Imagem liberada " + (index + 1));
        if (image) grid.append(image);
      });
      setVisible(grid, grid.childElementCount > 0);
    }
  }

  function renderHints(game) {
    const container = byId("hints");
    if (!container) return;
    clear(container);
    if (!game.hints || !game.hints.length) {
      container.append(node("div", { className: "empty", text: "Nenhuma pista textual liberada." }));
      return;
    }
    game.hints.forEach(function (hint) {
      container.append(node("div", { className: "hint" }, [
        node("b", { text: "#" + formatValue(hint.position) }),
        node("span", { text: formatValue(hint.hintValue) })
      ]));
    });
  }

  function renderAnswer(game) {
    const container = byId("answer-panel");
    if (!container) return;
    clear(container);
    if (!game.answer) {
      setVisible(container, false);
      return;
    }
    const answer = game.answer;
    const copy = [answer.title, answer.seriesName, answer.seasonNumber ? "Temporada " + answer.seasonNumber : null, answer.episodeNumber ? "Episódio " + answer.episodeNumber : null].filter(Boolean).join(" · ");
    const image = mediaImage(answer.imageUrl || answer.seriesPosterUrl, "Resposta revelada");
    container.append(node("h3", { text: "Resposta revelada" }), node("div", { className: "answer-content" }, [
      image,
      node("div", {}, [node("strong", { text: copy || "Resposta sem título" }), node("small", { className: "subtle", text: answer.targetKind || "" })])
    ]));
    setVisible(container, true);
  }

  function renderAttempts(game) {
    const container = byId("attempts-log");
    if (!container) return;
    clear(container);
    const attempts = game.attempts || [];
    if (!attempts.length) {
      container.append(node("div", { className: "empty", text: "As tentativas aparecerão aqui." }));
      return;
    }
    attempts.forEach(function (attempt) {
      const candidate = attempt.candidate || {};
      const feedback = attempt.episodeFeedback || attempt.infoFeedback || attempt.filmographyFeedback;
      const summary = attempt.infoFeedback ? "Comparação de informações recebida" : attempt.filmographyFeedback ? "Filmografia comparada" : attempt.episodeFeedback ? (attempt.episodeFeedback.exactMatch ? "Acerto exato" : "Coordenada comparada") : "Palpite enviado";
      container.append(node("div", { className: "attempt-card" }, [
        node("strong", { text: "Tentativa " + attempt.attemptNumber + " · " + (candidate.title || candidate.name || "candidato selecionado") }),
        node("span", { className: "subtle", text: summary }),
        feedback && attempt.infoFeedback ? node("span", { className: "subtle", text: "Veja a tabela de comparação abaixo." }) : null
      ]));
    });
  }

  function renderEpisodeFeedback(game) {
    const container = byId("guess-feedback");
    if (!container) return;
    const latest = game.currentAttempt && game.currentAttempt.episodeFeedback
      ? game.currentAttempt.episodeFeedback
      : (game.attempts || []).slice().reverse().find(function (attempt) {
          return attempt.episodeFeedback;
        });
    const feedback = game.guessFeedback || (latest && latest.episodeFeedback);
    clear(container);
    if (!feedback) {
      setVisible(container, false);
      return;
    }
    container.append(node("div", { className: "panel-heading" }, [
      node("h2", { text: "Feedback do palpite" }),
      node("span", { className: "subtle", text: feedback.exactMatch ? "Acerto completo" : "Parte da coordenada conferida" })
    ]));
    const values = [
      ["Série", feedback.seriesCorrect],
      ["Temporada", feedback.seasonCorrect],
      ["Episódio", feedback.episodeCorrect]
    ];
    const grid = node("div", { className: "feedback-grid" });
    values.forEach(function ([label, correct]) {
      grid.append(node("div", { className: "feedback-item " + (correct ? "feedback-good" : "feedback-bad") }, [
        node("strong", { text: label }),
        node("span", { text: correct ? "Acertou" : "Errou" })
      ]));
    });
    container.append(grid);
    setVisible(container, true);
  }

  function renderInfoTable(game) {
    const body = byId("info-table-body");
    if (!body) return;
    clear(body);
    (game.attempts || []).forEach(function (attempt) {
      if (!attempt.infoFeedback) return;
      const row = node("tr");
      const candidate = attempt.candidate || {};
      row.append(node("td", {}, [node("strong", { text: candidate.title || "Candidato" }), node("small", { className: "subtle", text: "Tentativa " + attempt.attemptNumber })]));
      COMPARISON_FIELDS.forEach(function ([key]) {
        const cell = attempt.infoFeedback[key];
        const content = node("div", { className: "compare-cell " + (cell && cell.status ? cell.status : "NO_DATA") });
        if (!cell) {
          content.append(node("span", { className: "compare-value", text: "—" }));
        } else {
          content.append(node("span", { className: "compare-value", text: formatValue(cell.displayValue) }));
          if (cell.matchedValues && cell.matchedValues.length) content.append(node("span", { className: "compare-meta", text: "Comum: " + cell.matchedValues.join(", ") }));
          if (cell.matchCount !== null && cell.matchCount !== undefined) content.append(node("span", { className: "compare-meta", text: "Correspondências: " + cell.matchCount }));
          if (cell.direction) content.append(node("span", { className: "compare-meta", text: cell.direction === "SECRET_HIGHER" ? "Segredo é maior" : "Segredo é menor" }));
        }
        row.append(node("td", {}, [content]));
      });
      body.append(row);
    });
  }

  function renderFilmography(game) {
    const grid = byId("filmography-grid");
    const actors = byId("guessed-actors");
    if (!grid && !actors) return;
    const filmography = game.filmography || {};
    if (actors) {
      clear(actors);
      (filmography.guessedActors || []).forEach(function (actor) {
        actors.append(node("span", { className: "status status-in_progress", text: actor.name || actor.personTmdbId }));
      });
      if (!actors.childElementCount) actors.append(node("span", { className: "empty", text: "Nenhum ator pesquisado ainda." }));
    }
    if (grid) {
      clear(grid);
      (filmography.entries || []).forEach(function (entry) {
        const title = entry.revealed ? (entry.title || "Obra revelada") : "Obra oculta";
        const details = [entry.year, entry.genres && entry.genres.length ? entry.genres.join(", ") : null, entry.episodeCount ? entry.episodeCount + " episódios" : null].filter(Boolean).join(" · ");
        const cardChildren = [node("div", { className: "film-card-body" }, [
          node("strong", { text: title }),
          node("small", { text: details || "Metadados não disponíveis" }),
        ])];
        grid.append(node("article", { className: "film-card" + (entry.highlighted ? " highlighted" : "" ) }, cardChildren));
      });
      if (!grid.childElementCount) grid.append(node("div", { className: "empty", text: "A filmografia aparecerá após o primeiro palpite." }));
    }
  }

  function setGameControlsDisabled(disabled) {
    ["submit-button", "give-up-button", "share-on-completion", "filmography-toggle", "search-query", "search-submit", "series-query", "series-search-submit", "season-select", "episode-select"].forEach(function (id) {
      const element = byId(id);
      if (element) element.disabled = disabled;
    });
  }

  function renderGame(game) {
    state.game = game;
    const status = byId("game-status");
    if (status) { clear(status); status.append(statusBadge(game.status)); }
    const title = byId("game-title");
    if (title && state.config) title.textContent = state.config.title;
    const date = byId("game-date");
    if (date) date.textContent = state.config && state.config.date ? state.config.date : "Desafio de hoje";
    renderStats(game);
    renderImages(game);
    renderEpisodeFeedback(game);
    renderHints(game);
    renderAttempts(game);
    renderInfoTable(game);
    renderFilmography(game);
    renderAnswer(game);
    const share = byId("share-on-completion");
    if (share) share.checked = Boolean(game.shareOnCompletion);
    setGameControlsDisabled(game.status === "COMPLETED" || game.status === "FAILED");
    clearError("game-error");
  }

  function candidateRequest(candidate, share) {
    const body = { shareOnCompletion: Boolean(share) };
    if (state.config.targetKind === "PERSON") body.personTmdbId = candidate.personTmdbId;
    else body.tmdbId = candidate.tmdbId;
    return body;
  }

  function renderCandidateResults(results, onSelect) {
    const container = byId("search-results") || byId("series-results");
    if (!container) return;
    clear(container);
    const items = (results && results.content) || [];
    if (!items.length) {
      container.append(node("div", { className: "empty", text: "Nenhum resultado encontrado." }));
      return;
    }
    items.forEach(function (candidate) {
      const button = node("button", { className: "secondary", text: "Selecionar" });
      button.addEventListener("click", function () { onSelect(candidate); });
      const image = mediaImage(candidate.imageUrl, candidate.title || "Candidato", "mini-poster");
      container.append(node("article", { className: "result-card" }, [
        image || node("div", { className: "mini-poster" }),
        node("div", { className: "result-copy" }, [node("strong", { text: candidate.title || "Sem título" }), node("small", { text: candidate.date || candidate.targetKind || "" })]),
        button
      ]));
    });
  }

  function bindGenericSearch() {
    const form = byId("search-form");
    if (!form) return;
    form.addEventListener("submit", async function (event) {
      event.preventDefault();
      clearError("game-error");
      const query = (byId("search-query").value || "").trim();
      if (!query) { showError("Digite algo para pesquisar.", "game-error"); return; }
      const button = byId("search-submit");
      if (button) button.disabled = true;
      try {
        const results = await api.search(state.config.gameType, query);
        renderCandidateResults(results, function (candidate) {
          state.selectedCandidate = candidate;
          const selected = byId("selected-candidate");
          if (selected) selected.textContent = "Selecionado: " + (candidate.title || "candidato");
        });
      } catch (error) {
        showError(displayError(error), "game-error");
      } finally {
        if (button) button.disabled = false;
      }
    });
  }

  function bindGenericActions() {
    const submit = byId("submit-button");
    if (submit) submit.addEventListener("click", async function () {
      if (!state.selectedCandidate) { showError("Selecione um candidato antes de enviar.", "game-error"); return; }
      await submitBody(candidateRequest(state.selectedCandidate, byId("share-on-completion") && byId("share-on-completion").checked));
    });
    const giveUp = byId("give-up-button");
    if (giveUp) giveUp.addEventListener("click", async function () {
      if (!window.confirm("Desistir desta partida?")) return;
      try {
        giveUp.disabled = true;
        renderGame(await api.giveUp(state.config.gameType));
      } catch (error) {
        showError(displayError(error), "game-error");
        giveUp.disabled = false;
      }
    });
  }

  async function submitBody(body) {
    clearError("game-error");
    const button = byId("submit-button");
    if (button) button.disabled = true;
    try {
      const response = await api.submitAttempt(state.config.gameType, body, state.config.majorRoles ? state.majorRoles : undefined);
      state.selectedCandidate = null;
      const selected = byId("selected-candidate");
      if (selected) selected.textContent = "";
      renderGame(response);
    } catch (error) {
      showError(displayError(error), "game-error");
      if (button) button.disabled = false;
    }
  }

  function bindEpisodePage() {
    const form = byId("series-search-form");
    const seasonSelect = byId("season-select");
    const episodeSelect = byId("episode-select");
    const searchButton = byId("series-search-submit");
    if (form) form.addEventListener("submit", async function (event) {
      event.preventDefault();
      const query = (byId("series-query").value || "").trim();
      if (!query) { showError("Digite uma série para pesquisar.", "game-error"); return; }
      if (searchButton) searchButton.disabled = true;
      try {
        renderCandidateResults(await api.searchEpisodeSeries(query), function (series) {
          state.selectedSeries = series;
          state.selectedEpisode = null;
          state.episodeOptions = [];
          clear(byId("selected-episode"));
          const selected = byId("selected-series");
          if (selected) selected.textContent = "Série selecionada: " + (series.title || series.tmdbId);
          if (seasonSelect) { seasonSelect.disabled = true; clear(seasonSelect); seasonSelect.append(node("option", { value: "", text: "Carregando temporadas…" })); }
          if (episodeSelect) { episodeSelect.disabled = true; clear(episodeSelect); episodeSelect.append(node("option", { value: "", text: "Selecione uma temporada" })); }
          loadSeasons(series.seriesTmdbId || series.tmdbId);
        });
      } catch (error) {
        showError(displayError(error), "game-error");
      } finally {
        if (searchButton) searchButton.disabled = false;
      }
    });
    if (seasonSelect) seasonSelect.addEventListener("change", function () {
      const seasonNumber = Number(seasonSelect.value);
      if (!state.selectedSeries || !seasonNumber) return;
      loadEpisodes(state.selectedSeries.seriesTmdbId || state.selectedSeries.tmdbId, seasonNumber);
    });
    if (episodeSelect) episodeSelect.addEventListener("change", function () {
      const episodeNumber = Number(episodeSelect.value);
      state.selectedEpisode = state.episodeOptions.find(function (episode) { return episode.episodeNumber === episodeNumber; }) || null;
      const selected = byId("selected-episode");
      if (selected) selected.textContent = state.selectedEpisode ? "Episódio selecionado: " + state.selectedEpisode.name : "";
    });
    const submit = byId("submit-button");
    if (submit) submit.addEventListener("click", function () {
      if (!state.selectedSeries || !state.selectedEpisode) { showError("Selecione a série, a temporada e o episódio.", "game-error"); return; }
      submitBody({
        seriesTmdbId: state.selectedEpisode.seriesTmdbId || state.selectedSeries.seriesTmdbId || state.selectedSeries.tmdbId,
        seasonNumber: state.selectedEpisode.seasonNumber,
        episodeNumber: state.selectedEpisode.episodeNumber,
        shareOnCompletion: Boolean(byId("share-on-completion") && byId("share-on-completion").checked)
      });
    });
    const giveUp = byId("give-up-button");
    if (giveUp) giveUp.addEventListener("click", async function () {
      if (!window.confirm("Desistir desta partida?")) return;
      try { giveUp.disabled = true; renderGame(await api.giveUp(state.config.gameType)); }
      catch (error) { showError(displayError(error), "game-error"); giveUp.disabled = false; }
    });
  }

  async function loadSeasons(seriesTmdbId) {
    try {
      state.episodeSeasons = await api.listEpisodeSeasons(seriesTmdbId);
      const select = byId("season-select");
      if (!select) return;
      clear(select);
      select.append(node("option", { value: "", text: "Selecione uma temporada" }));
      state.episodeSeasons.forEach(function (season) { select.append(node("option", { value: season.seasonNumber, text: "Temporada " + season.seasonNumber + " · " + season.name + " (" + season.episodeCount + ")" })); });
      select.disabled = false;
    } catch (error) { showError(displayError(error), "game-error"); }
  }

  async function loadEpisodes(seriesTmdbId, seasonNumber) {
    try {
      state.episodeOptions = await api.listEpisodeEpisodes(seriesTmdbId, seasonNumber);
      const select = byId("episode-select");
      if (!select) return;
      clear(select);
      select.append(node("option", { value: "", text: "Selecione um episódio" }));
      state.episodeOptions.forEach(function (episode) { select.append(node("option", { value: episode.episodeNumber, text: "Episódio " + episode.episodeNumber + " · " + episode.name + (episode.airDate ? " · " + episode.airDate : "") })); });
      select.disabled = false;
    } catch (error) { showError(displayError(error), "game-error"); }
  }

  function bindMajorRoles() {
    const toggle = byId("filmography-toggle");
    if (!toggle) return;
    state.majorRoles = toggle.checked;
    toggle.addEventListener("change", async function () {
      state.majorRoles = toggle.checked;
      try { renderGame(await api.getGameToday(state.config.gameType, state.majorRoles)); }
      catch (error) { showError(displayError(error), "game-error"); }
    });
  }

  async function loadGame() {
    try {
      const game = await api.getGameToday(state.config.gameType, state.config.majorRoles ? state.majorRoles : undefined);
      renderGame(game);
    } catch (error) {
      showError(displayError(error), "game-error");
    }
  }

  function renderDashboard(data, history, ranking) {
    const date = byId("dashboard-date");
    if (date) date.textContent = data && data.date ? data.date : "Data indisponível";
    const grid = byId("games-grid");
    if (grid) {
      clear(grid);
      (data && data.games || []).forEach(function (game) {
        const meta = GAME_META[game.gameType] || { label: game.gameType, href: "#", target: game.targetKind };
        const link = node("a", { href: meta.href, text: meta.label });
        grid.append(node("article", { className: "game-card" }, [
          link,
          node("span", { className: "subtle", text: meta.target + " · " + game.attemptsRemaining + " tentativa(s) restante(s)" }),
          node("div", { className: "card-meta" }, [statusBadge(game.status), node("small", { text: "Score " + game.score })])
        ]));
      });
      if (!grid.childElementCount) grid.append(node("div", { className: "empty", text: "Nenhum jogo foi retornado pelo backend." }));
    }
    renderHistory(history);
    renderRanking(ranking);
  }

  function renderHistory(page) {
    const list = byId("history-list");
    if (!list) return;
    clear(list);
    (page && page.content || []).forEach(function (item) {
      list.append(node("li", {}, [node("strong", { text: item.challengeDate + " · " + item.gameType }), node("span", { text: statusLabel(item.status) + " · " + item.score + " pts" })]));
    });
    if (!list.childElementCount) list.append(node("li", { className: "empty", text: "Nenhum resultado no histórico." }));
    if (page && page.hasNext) {
      const button = node("button", { className: "secondary", text: "Próxima página" });
      button.addEventListener("click", async function () {
        button.disabled = true;
        try { renderHistory(await api.getHistory((page.page || 1) + 1)); }
        catch (error) { showError(displayError(error), "page-error"); button.disabled = false; }
      });
      list.append(node("li", {}, [button]));
    }
  }

  function renderRanking(page) {
    const list = byId("ranking-list");
    if (!list) return;
    clear(list);
    (page && page.content || []).forEach(function (item) {
      list.append(node("li", {}, [node("strong", { text: "#" + item.rank + " · " + item.username }), node("span", { text: item.score + " pts · " + item.gamesPlayed + " jogos" })]));
    });
    if (!list.childElementCount) list.append(node("li", { className: "empty", text: "Ranking vazio." }));
    if (page && page.hasNext) {
      const button = node("button", { className: "secondary", text: "Próxima página" });
      button.addEventListener("click", async function () {
        button.disabled = true;
        try { renderRanking(await api.getGeneralRanking((page.page || 1) + 1)); }
        catch (error) { showError(displayError(error), "page-error"); button.disabled = false; }
      });
      list.append(node("li", {}, [button]));
    }
  }

  async function loadDashboard() {
    try {
      const data = await api.getToday();
      const results = await Promise.allSettled([api.getHistory(), api.getGeneralRanking()]);
      renderDashboard(data, results[0].status === "fulfilled" ? results[0].value : null, results[1].status === "fulfilled" ? results[1].value : null);
      setVisible(byId("dashboard-panel"), true);
      clearError("page-error");
    } catch (error) {
      showError(displayError(error), "page-error");
      if (error.status === 401) showLoggedOut();
    }
  }

  async function login(event) {
    event.preventDefault();
    clearError("page-error");
    const button = byId("login-button");
    try {
      if (button) button.disabled = true;
      const user = await api.login(byId("login-identifier").value.trim(), byId("login-password").value);
      showSession(user);
      await loadDashboard();
    } catch (error) {
      showError(displayError(error), "page-error");
    } finally {
      if (button) button.disabled = false;
      const password = byId("login-password");
      if (password) password.value = "";
    }
  }

  async function logout() {
    try { await api.logout(); } catch (error) { /* logout should still clear the local view */ }
    const password = byId("login-password");
    if (password) password.value = "";
    showLoggedOut();
    setVisible(byId("dashboard-panel"), false);
    if (state.config && state.config.page === "game") setVisible(byId("game-panel"), false);
  }

  function bindCommon() {
    const logoutButton = byId("logout-button");
    if (logoutButton) logoutButton.addEventListener("click", logout);
    const reload = byId("reload-button");
    if (reload) reload.addEventListener("click", function () {
      if (state.config.page === "dashboard") loadDashboard(); else loadGame();
    });
    const loginForm = byId("login-form");
    if (loginForm) loginForm.addEventListener("submit", login);
  }

  async function restoreSession() {
    try {
      const user = await api.getCurrentUser();
      showSession(user);
      return true;
    } catch (error) {
      showLoggedOut();
      return false;
    }
  }

  async function start(config) {
    state.config = Object.assign({}, config);
    state.majorRoles = state.config.majorRoles ? true : false;
    bindCommon();
    if (state.config.page === "dashboard") {
      const authenticated = await restoreSession();
      if (authenticated) await loadDashboard();
      return;
    }
    if (state.config.mode !== "episode") {
      bindGenericSearch();
      bindGenericActions();
    }
    if (state.config.mode === "episode") bindEpisodePage();
    if (state.config.majorRoles) bindMajorRoles();
    const authenticated = await restoreSession();
    if (authenticated) {
      setVisible(byId("game-panel"), true);
      await loadGame();
    } else {
      showError("Abra o painel para fazer login antes de jogar.", "game-error");
    }
  }

  window.GameTestUi = { start: start, api: api };
}());
