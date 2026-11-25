package com.brunosong.system.digitaltwin.web;

import com.brunosong.system.digitaltwin.persona.PersonaResources;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class TwinPageController {

    private final PersonaResources resources;

    public TwinPageController(PersonaResources resources) {
        this.resources = resources;
    }

    @GetMapping("/")
    public String twin(Model model) {
        model.addAttribute("name", resources.name());
        model.addAttribute("fullName", resources.fullName());
        return "twin";
    }
}
