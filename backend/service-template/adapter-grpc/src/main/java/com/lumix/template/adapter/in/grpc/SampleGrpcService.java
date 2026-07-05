package com.lumix.template.adapter.in.grpc;

import com.lumix.template.application.port.in.CreateSampleCommand;
import com.lumix.template.application.port.in.CreateSampleUseCase;
import com.lumix.template.domain.model.SampleId;
import com.lumix.template.grpc.v1.CreateSampleRequest;
import com.lumix.template.grpc.v1.CreateSampleResponse;
import com.lumix.template.grpc.v1.SampleServiceGrpc;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;

/**
 * Inbound gRPC adapter — REST ile AYNI inbound port'u (use case) çağırır.
 * Üretilen tipler ({@code CreateSampleRequest} vb.) sample.proto'dan gelir (build/generated).
 */
@GrpcService
public class SampleGrpcService extends SampleServiceGrpc.SampleServiceImplBase {

    private final CreateSampleUseCase createSampleUseCase;

    public SampleGrpcService(CreateSampleUseCase createSampleUseCase) {
        this.createSampleUseCase = createSampleUseCase;
    }

    @Override
    public void createSample(CreateSampleRequest request, StreamObserver<CreateSampleResponse> responseObserver) {
        SampleId id = createSampleUseCase.create(new CreateSampleCommand(request.getName()));
        responseObserver.onNext(
                CreateSampleResponse.newBuilder().setId(id.value().toString()).build());
        responseObserver.onCompleted();
    }
}
