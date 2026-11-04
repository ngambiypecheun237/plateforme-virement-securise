package com.banque.virement.config;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Redirections de confort : la racine et /admin menent au tableau de bord d'administration.
 */
@Controller
public class WebRedirectController {

    @GetMapping("/")
    public String root() {
        return "redirect:/admin/index.html";
    }

    @GetMapping({"/admin", "/admin/"})
    public String admin() {
        return "redirect:/admin/index.html";
    }
}
