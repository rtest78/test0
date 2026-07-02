package com.example.collections.controller;

import com.example.collections.service.CollectionsDataService;
import com.example.collections.service.CollectionsDataService.AccountDayView;
import com.example.collections.service.CollectionsDataService.ComparisonSummary;
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

    private static final int MAXIMUM_DISPLAY_ROWS = 500;

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

        String normalizedMember =
                memberNumber == null
                        ? ""
                        : memberNumber.trim();

        String normalizedStatus =
                status == null
                        ? ""
                        : status.trim().toUpperCase();

        DailySnapshot snapshot =
                selectedDate == null
                        ? null
                        : dataService.getSnapshot(
                                selectedDate
                        );

        /*
         * Counts are displayed even before account rows are loaded.
         */
        ComparisonSummary comparisonSummary =
                selectedDate == null
                        ? new ComparisonSummary()
                        : dataService.getComparisonSummary(
                                selectedDate,
                                normalizedMember
                        );

        boolean hasSearchCriteria =
                !normalizedMember.isEmpty()
                        || !normalizedStatus.isEmpty();

        List<AccountDayView> accountViews =
                new ArrayList<AccountDayView>();

        if (selectedDate != null
                && hasSearchCriteria) {

            accountViews =
                    dataService.findAccountDayViews(
                            selectedDate,
                            normalizedMember
                    );

            if (!normalizedStatus.isEmpty()) {

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
        }

        int matchedCount =
                accountViews.size();

        boolean resultsTruncated =
                matchedCount > MAXIMUM_DISPLAY_ROWS;

        if (resultsTruncated) {

            accountViews =
                    new ArrayList<AccountDayView>(
                            accountViews.subList(
                                    0,
                                    MAXIMUM_DISPLAY_ROWS
                            )
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
                normalizedMember
        );

        model.addAttribute(
                "status",
                normalizedStatus
        );

        model.addAttribute(
                "snapshot",
                snapshot
        );

        model.addAttribute(
                "comparisonSummary",
                comparisonSummary
        );

        model.addAttribute(
                "accounts",
                accountViews
        );

        model.addAttribute(
                "hasSearchCriteria",
                hasSearchCriteria
        );

        model.addAttribute(
                "resultsTruncated",
                resultsTruncated
        );

        model.addAttribute(
                "matchedCount",
                matchedCount
        );

        model.addAttribute(
                "displayedCount",
                accountViews.size()
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
