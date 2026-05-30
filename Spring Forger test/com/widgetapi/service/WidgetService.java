package com.widgetapi.service;

import com.widgetapi.dto.WidgetDTO;
import com.widgetapi.entity.Widget;
import com.widgetapi.exception.ResourceNotFoundException;
import com.widgetapi.repository.WidgetRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class WidgetService {

    private final WidgetRepository widgetRepository;

    public Page<WidgetDTO> getAllWidgets(Pageable pageable) {
        return widgetRepository.findAll(pageable)
                .map(this::mapToDTO);
    }

    public WidgetDTO getWidgetById(Long id) {
        Widget widget = widgetRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Widget not found with id: " + id));
        return mapToDTO(widget);
    }

    @Transactional
    public WidgetDTO createWidget(WidgetDTO dto) {
        Widget widget = Widget.builder()
                .name(dto.getName())
                .price(dto.getPrice())
                .build();
        Widget savedWidget = widgetRepository.save(widget);
        return mapToDTO(savedWidget);
    }

    @Transactional
    public WidgetDTO updateWidget(Long id, WidgetDTO dto) {
        Widget widget = widgetRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Widget not found with id: " + id));
        
        widget.setName(dto.getName());
        widget.setPrice(dto.getPrice());
        
        Widget updatedWidget = widgetRepository.save(widget);
        return mapToDTO(updatedWidget);
    }

    @Transactional
    public void deleteWidget(Long id) {
        if (!widgetRepository.existsById(id)) {
            throw new ResourceNotFoundException("Widget not found with id: " + id);
        }
        widgetRepository.deleteById(id);
    }

    private WidgetDTO mapToDTO(Widget widget) {
        return WidgetDTO.builder()
                .id(widget.getId())
                .name(widget.getName())
                .price(widget.getPrice())
                .build();
    }
}