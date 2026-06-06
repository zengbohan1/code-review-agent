package com.shortdrama.agent.cost;

import com.shortdrama.agent.common.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 成本统计接口：GET /api/cost 返回累计 token 用量与按 DeepSeek 定价折算的美元成本。
 */
@RestController
@RequestMapping("/api/cost")
public class CostController {

    private final TokenCostService costService;

    public CostController(TokenCostService costService) {
        this.costService = costService;
    }

    @GetMapping
    public Result<TokenCostService.CostSnapshot> cost() {
        return Result.ok(costService.snapshot());
    }
}
