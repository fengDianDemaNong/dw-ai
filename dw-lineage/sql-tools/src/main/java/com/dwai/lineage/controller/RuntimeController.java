package com.dwai.lineage.controller;

import com.dwai.lineage.conf.LineageProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping({"/api", "/api/v1"})
public class RuntimeController {
    private final LineageProperties props;

    public RuntimeController(LineageProperties props) {
        this.props = props;
    }

    @GetMapping("/runtime")
    public Map<String, Object> runtime() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("product", "metadata");
        out.put("runMode", props.runMode());
        out.put("standalone", props.isStandalone());
        out.put("standard", props.isStandard());
        out.put("multi", props.isMulti());
        return out;
    }
}
