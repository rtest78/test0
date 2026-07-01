package com.example.collections.controller;

import com.example.collections.model.DailySnapshot;
import com.example.collections.service.CollectionsDataService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.util.List;

@Controller
public class CollectionsController {

    private final CollectionsDataService dataService;

    public CollectionsController(CollectionsDataService dataService) {
        this.dataService = dataService;
    }

    @GetMapping("/")
    public String dashboard(Model model) {
        model.addAttribute("summaries", dataService.summaries());
        model.addAttribute("availableDates", dataService.availableDates());
        model.addAttribute("dataLoaded", !dataService.availableDates().isEmpty());
        return "dashboard";
    }

    @GetMapping("/accounts")
    public String accounts(
            @RequestParam(required = false) String date,
            @RequestParam(required = false) String memberNumber,
            Model model
    ) {
        List<String> dates = dataService.availableDates();
        String selectedDate = date;
        if ((selectedDate == null || selectedDate.isBlank()) && !dates.isEmpty()) {
            selectedDate = dates.get(dates.size() - 1);
        }

        DailySnapshot snapshot = selectedDate == null
                ? null
                : dataService.snapshot(selectedDate).orElse(null);

        model.addAttribute("availableDates", dates);
        model.addAttribute("selectedDate", selectedDate);
        model.addAttribute("memberNumber", memberNumber == null ? "" : memberNumber.trim());
        model.addAttribute("snapshot", snapshot);
        model.addAttribute("accounts", selectedDate == null
                ? List.of()
                : dataService.accountsForDate(selectedDate, memberNumber));
        model.addAttribute("missingAccounts", selectedDate == null
                ? List.of()
                : dataService.missingAccountsForDate(selectedDate, memberNumber));
        return "accounts";
    }

    @PostMapping("/reload")
    public String reload(RedirectAttributes redirectAttributes) {
        try {
            dataService.reload();
            redirectAttributes.addFlashAttribute("message", "Daily JSON files reloaded successfully.");
        } catch (IOException ex) {
            redirectAttributes.addFlashAttribute("error", "Reload failed: " + ex.getMessage());
        }
        return "redirect:/";
    }
}
