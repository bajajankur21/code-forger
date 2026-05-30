package com.widgetapi.controller;

import com.widgetapi.dto.WidgetDTO;
import com.widgetapi.service.WidgetService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/widgets")
@RequiredArgsConstructor
public class WidgetController {

    private final WidgetService widgetService;

    @GetMapping
    public ResponseEntity<Page<WidgetDTO>> listAll(Pageable pageable) {
        return ResponseEntity.ok(widgetService.getAllWidgets(pageable));
    }

    @PostMapping
    public ResponseEntity<WidgetDTO> create(@Valid @RequestBody WidgetDTO dto) {
        return new ResponseEntity<>(widgetService.createWidget(dto), HttpStatus.CREATED);
    }

    @GetMapping("/{id}")
    public ResponseEntity<WidgetDTO> read(@PathVariable Long id) {
        return ResponseEntity.ok(widgetService.getWidgetById(id));
    }

    @PutMapping("/{id}")
    public ResponseEntity<WidgetDTO> update(@PathVariable Long id, @Valid @RequestBody WidgetDTO dto) {
        return ResponseEntity.ok(widgetService.updateWidget(id, dto));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        widgetService.deleteWidget(id);
        return ResponseEntity.noContent().build();
    }
}