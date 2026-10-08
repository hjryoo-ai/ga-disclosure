package com.ga.disclosure.api.dto;

import java.util.List;

public record LegalHoldPage(List<LegalHoldView> items, String next) {
}
