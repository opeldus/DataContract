package de.opeldus.datacontract.ddl;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DdlController {

    private final DdlIndex index;

    public DdlController(DdlIndex index) {
        this.index = index;
    }

    @GetMapping("/api/ddl/status")
    public DdlIndex.Status status() {
        return index.status();
    }
}
