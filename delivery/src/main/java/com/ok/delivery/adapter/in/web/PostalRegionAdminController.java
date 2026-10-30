package com.ok.delivery.adapter.in.web;

import com.ok.common.security.CurrentMemberId;
import com.ok.delivery.adapter.in.web.dto.PostalRegionPageRequest;
import com.ok.delivery.adapter.in.web.dto.PostalRegionPageResponse;
import com.ok.delivery.adapter.in.web.dto.PostalRegionRegisterRequest;
import com.ok.delivery.adapter.in.web.dto.PostalRegionResponse;
import com.ok.delivery.adapter.in.web.dto.PostalRegionUpdateRequest;
import com.ok.delivery.application.port.in.DeliveryRegionUseCase;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/postal-regions")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class PostalRegionAdminController implements PostalRegionAdminControllerSpec {

    private final DeliveryRegionUseCase deliveryRegionUseCase;

    @Override
    @PostMapping
    public ResponseEntity<PostalRegionResponse> register(
            @CurrentMemberId Long adminId,
            @Valid @RequestBody PostalRegionRegisterRequest request
    ) {
        PostalRegionResponse response = PostalRegionResponse.from(deliveryRegionUseCase.register(request.toCommand(), adminId));
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Override
    @PutMapping("/{postalCode}")
    public ResponseEntity<PostalRegionResponse> update(
            @CurrentMemberId Long adminId,
            @PathVariable String postalCode,
            @Valid @RequestBody PostalRegionUpdateRequest request
    ) {
        PostalRegionResponse response = PostalRegionResponse.from(
                deliveryRegionUseCase.update(request.toCommand(postalCode), adminId));
        return ResponseEntity.ok(response);
    }

    @Override
    @DeleteMapping("/{postalCode}")
    public ResponseEntity<Void> delete(
            @CurrentMemberId Long adminId,
            @PathVariable String postalCode
    ) {
        deliveryRegionUseCase.delete(postalCode, adminId);
        return ResponseEntity.noContent().build();
    }

    @Override
    @GetMapping
    public ResponseEntity<PostalRegionPageResponse> getPostalRegions(
            @Valid @ModelAttribute PostalRegionPageRequest request
    ) {
        return ResponseEntity.ok(PostalRegionPageResponse.from(
                deliveryRegionUseCase.getPostalRegions(request.page(), request.size())));
    }
}
