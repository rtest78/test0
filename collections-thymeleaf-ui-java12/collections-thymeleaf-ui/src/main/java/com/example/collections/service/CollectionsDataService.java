package com.example.collections.controller;

import com.example.collections.service.CollectionsDataService;
import com.example.collections.service.CollectionsDataService.AccountDayView;
import com.example.collections.service.CollectionsDataService.DailySnapshot;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Controller
public class CollectionsController {

    private final CollectionsDataService dataService;

    public CollectionsController(
            CollectionsDataService dataService) {

        this.dataService = dataService;
    }

    @GetMapping("/")
    public String dashboard(Model model) {

        model.addAttribute(
                "summaries",
                dataService.getDailySummaries()
        );

        model.addAttribute(
                "dataDirectory",
                dataService.getDataDirectory()
        );

        model.addAttribute(
                "loadedDateCount",
                dataService.getLoadedDateCount()
        );

        return "dashboard";
    }

    @GetMapping("/accounts")
    public String accounts(
            @RequestParam(
                    name = "date",
                    required = false
            )
            String date,

            @RequestParam(
                    name = "memberNumber",
                    required = false
            )
            String memberNumber,

            @RequestParam(
                    name = "status",
                    required = false
            )
            String status,

            Model model) {

        List<String> dates =
                dataService.getAvailableDates();

        String selectedDate =
                dataService.resolveDate(date);

        DailySnapshot snapshot =
                selectedDate == null
                        ? null
                        : dataService.getSnapshot(
                                selectedDate
                        );

        List<AccountDayView> accountViews =
                selectedDate == null
                        ? new ArrayList<AccountDayView>()
                        : dataService.findAccountDayViews(
                                selectedDate,
                                memberNumber
                        );

        if (status != null
                && !status.trim().isEmpty()) {

            String normalizedStatus =
                    status.trim().toUpperCase();

            accountViews =
                    accountViews.stream()
                            .filter(view ->
                                    view.getChangeType()
                                            .name()
                                            .equals(
                                                    normalizedStatus
                                            )
                            )
                            .collect(
                                    Collectors.toList()
                            );
        }

        model.addAttribute(
                "availableDates",
                dates
        );

        model.addAttribute(
                "selectedDate",
                selectedDate
        );

        model.addAttribute(
                "memberNumber",
                memberNumber == null
                        ? ""
                        : memberNumber.trim()
        );

        model.addAttribute(
                "status",
                status == null
                        ? ""
                        : status.trim().toUpperCase()
        );

        model.addAttribute(
                "snapshot",
                snapshot
        );

        model.addAttribute(
                "accounts",
                accountViews
        );

        return "accounts";
    }

    @PostMapping("/reload")
    public String reload(
            RedirectAttributes redirectAttributes) {

        try {

            dataService.reload();

            redirectAttributes.addFlashAttribute(
                    "message",
                    "Daily JSON files reloaded successfully."
            );

        } catch (IOException ex) {

            redirectAttributes.addFlashAttribute(
                    "error",
                    "Reload failed: "
                            + ex.getMessage()
            );
        }

        return "redirect:/";
    }
}
