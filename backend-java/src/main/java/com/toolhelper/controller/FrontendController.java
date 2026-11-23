package com.toolhelper.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class FrontendController {
    // 仅回退已知前端路由，不能把 /api 的 404 转成 HTML。
    @GetMapping({"/", "/tools/{tool}"})
    public String index() { return "forward:/index.html"; }
}
