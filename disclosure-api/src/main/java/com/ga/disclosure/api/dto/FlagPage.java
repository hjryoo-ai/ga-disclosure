package com.ga.disclosure.api.dto;

import java.util.List;

public record FlagPage(List<FlagView> items, String next) {
}
