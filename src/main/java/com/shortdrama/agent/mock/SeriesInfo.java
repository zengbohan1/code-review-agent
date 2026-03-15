package com.shortdrama.agent.mock;

import java.math.BigDecimal;

/**
 * 剧集信息（content 域 Series 语义：总集数、付费卡点、价格）。
 * 对接"剧集问题"场景：如"第几集开始收费""这部短剧多少集"。
 */
public record SeriesInfo(
        String seriesId,
        String title,
        int totalEpisodes,
        int freeEpisodes,           // 免费集数，之后为付费卡点
        BigDecimal pricePerEpisode,
        String currency) {
}
