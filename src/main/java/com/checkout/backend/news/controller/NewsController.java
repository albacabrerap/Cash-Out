package com.checkout.backend.news.controller;

import com.checkout.backend.news.dto.NewsResponses.NewsPageResponse;
import com.checkout.backend.news.dto.NewsResponses.TrendingAssetResponse;
import com.checkout.backend.news.service.NewsService;
import com.checkout.backend.user.service.CurrentUserProvider;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Noticias del mercado para el mural del portafolio. Llegan en ingles, como las publica la fuente. */
@RestController
@RequestMapping("/news")
public class NewsController {

    private final NewsService newsService;
    private final CurrentUserProvider currentUser;

    public NewsController(NewsService newsService, CurrentUserProvider currentUser) {
        this.newsService = newsService;
        this.currentUser = currentUser;
    }

    /** GET /api/v1/news?scope=mine|market|crypto&symbol=&pageToken= */
    @GetMapping
    public ResponseEntity<NewsPageResponse> feed(
            @RequestParam(defaultValue = "market") String scope,
            @RequestParam(required = false) String symbol,
            @RequestParam(required = false) String pageToken) {
        return ResponseEntity.ok(newsService.feed(currentUser.requireCurrentUser(), scope, symbol, pageToken));
    }

    /** GET /api/v1/news/trending: los activos que mas nombran las noticias de hoy. */
    @GetMapping("/trending")
    public ResponseEntity<List<TrendingAssetResponse>> trending() {
        return ResponseEntity.ok(newsService.trending());
    }

}
