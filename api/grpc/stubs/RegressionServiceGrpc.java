package stubs;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 */
@javax.annotation.Generated(
    value = "by gRPC proto compiler (version 1.45.0)",
    comments = "Source: service.proto")
@io.grpc.stub.annotations.GrpcGenerated
public final class RegressionServiceGrpc {

  private RegressionServiceGrpc() {}

  public static final String SERVICE_NAME = "services.RegressionService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<stubs.Service.endRequest,
      stubs.Service.endResponse> getEndMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "End",
      requestType = stubs.Service.endRequest.class,
      responseType = stubs.Service.endResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<stubs.Service.endRequest,
      stubs.Service.endResponse> getEndMethod() {
    io.grpc.MethodDescriptor<stubs.Service.endRequest, stubs.Service.endResponse> getEndMethod;
    if ((getEndMethod = RegressionServiceGrpc.getEndMethod) == null) {
      synchronized (RegressionServiceGrpc.class) {
        if ((getEndMethod = RegressionServiceGrpc.getEndMethod) == null) {
          RegressionServiceGrpc.getEndMethod = getEndMethod =
              io.grpc.MethodDescriptor.<stubs.Service.endRequest, stubs.Service.endResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "End"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  stubs.Service.endRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  stubs.Service.endResponse.getDefaultInstance()))
              .setSchemaDescriptor(new RegressionServiceMethodDescriptorSupplier("End"))
              .build();
        }
      }
    }
    return getEndMethod;
  }

  private static volatile io.grpc.MethodDescriptor<stubs.Service.startRequest,
      stubs.Service.startResponse> getStartMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "Start",
      requestType = stubs.Service.startRequest.class,
      responseType = stubs.Service.startResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<stubs.Service.startRequest,
      stubs.Service.startResponse> getStartMethod() {
    io.grpc.MethodDescriptor<stubs.Service.startRequest, stubs.Service.startResponse> getStartMethod;
    if ((getStartMethod = RegressionServiceGrpc.getStartMethod) == null) {
      synchronized (RegressionServiceGrpc.class) {
        if ((getStartMethod = RegressionServiceGrpc.getStartMethod) == null) {
          RegressionServiceGrpc.getStartMethod = getStartMethod =
              io.grpc.MethodDescriptor.<stubs.Service.startRequest, stubs.Service.startResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Start"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  stubs.Service.startRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  stubs.Service.startResponse.getDefaultInstance()))
              .setSchemaDescriptor(new RegressionServiceMethodDescriptorSupplier("Start"))
              .build();
        }
      }
    }
    return getStartMethod;
  }

  private static volatile io.grpc.MethodDescriptor<stubs.Service.getTCRequest,
      stubs.Service.TestCase> getGetTCMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "GetTC",
      requestType = stubs.Service.getTCRequest.class,
      responseType = stubs.Service.TestCase.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<stubs.Service.getTCRequest,
      stubs.Service.TestCase> getGetTCMethod() {
    io.grpc.MethodDescriptor<stubs.Service.getTCRequest, stubs.Service.TestCase> getGetTCMethod;
    if ((getGetTCMethod = RegressionServiceGrpc.getGetTCMethod) == null) {
      synchronized (RegressionServiceGrpc.class) {
        if ((getGetTCMethod = RegressionServiceGrpc.getGetTCMethod) == null) {
          RegressionServiceGrpc.getGetTCMethod = getGetTCMethod =
              io.grpc.MethodDescriptor.<stubs.Service.getTCRequest, stubs.Service.TestCase>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "GetTC"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  stubs.Service.getTCRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  stubs.Service.TestCase.getDefaultInstance()))
              .setSchemaDescriptor(new RegressionServiceMethodDescriptorSupplier("GetTC"))
              .build();
        }
      }
    }
    return getGetTCMethod;
  }

  private static volatile io.grpc.MethodDescriptor<stubs.Service.getTCSRequest,
      stubs.Service.getTCSResponse> getGetTCSMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "GetTCS",
      requestType = stubs.Service.getTCSRequest.class,
      responseType = stubs.Service.getTCSResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<stubs.Service.getTCSRequest,
      stubs.Service.getTCSResponse> getGetTCSMethod() {
    io.grpc.MethodDescriptor<stubs.Service.getTCSRequest, stubs.Service.getTCSResponse> getGetTCSMethod;
    if ((getGetTCSMethod = RegressionServiceGrpc.getGetTCSMethod) == null) {
      synchronized (RegressionServiceGrpc.class) {
        if ((getGetTCSMethod = RegressionServiceGrpc.getGetTCSMethod) == null) {
          RegressionServiceGrpc.getGetTCSMethod = getGetTCSMethod =
              io.grpc.MethodDescriptor.<stubs.Service.getTCSRequest, stubs.Service.getTCSResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "GetTCS"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  stubs.Service.getTCSRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  stubs.Service.getTCSResponse.getDefaultInstance()))
              .setSchemaDescriptor(new RegressionServiceMethodDescriptorSupplier("GetTCS"))
              .build();
        }
      }
    }
    return getGetTCSMethod;
  }

  private static volatile io.grpc.MethodDescriptor<stubs.Service.TestCaseReq,
      stubs.Service.postTCResponse> getPostTCMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "PostTC",
      requestType = stubs.Service.TestCaseReq.class,
      responseType = stubs.Service.postTCResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<stubs.Service.TestCaseReq,
      stubs.Service.postTCResponse> getPostTCMethod() {
    io.grpc.MethodDescriptor<stubs.Service.TestCaseReq, stubs.Service.postTCResponse> getPostTCMethod;
    if ((getPostTCMethod = RegressionServiceGrpc.getPostTCMethod) == null) {
      synchronized (RegressionServiceGrpc.class) {
        if ((getPostTCMethod = RegressionServiceGrpc.getPostTCMethod) == null) {
          RegressionServiceGrpc.getPostTCMethod = getPostTCMethod =
              io.grpc.MethodDescriptor.<stubs.Service.TestCaseReq, stubs.Service.postTCResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "PostTC"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  stubs.Service.TestCaseReq.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  stubs.Service.postTCResponse.getDefaultInstance()))
              .setSchemaDescriptor(new RegressionServiceMethodDescriptorSupplier("PostTC"))
              .build();
        }
      }
    }
    return getPostTCMethod;
  }

  private static volatile io.grpc.MethodDescriptor<stubs.Service.TestReq,
      stubs.Service.deNoiseResponse> getDeNoiseMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "DeNoise",
      requestType = stubs.Service.TestReq.class,
      responseType = stubs.Service.deNoiseResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<stubs.Service.TestReq,
      stubs.Service.deNoiseResponse> getDeNoiseMethod() {
    io.grpc.MethodDescriptor<stubs.Service.TestReq, stubs.Service.deNoiseResponse> getDeNoiseMethod;
    if ((getDeNoiseMethod = RegressionServiceGrpc.getDeNoiseMethod) == null) {
      synchronized (RegressionServiceGrpc.class) {
        if ((getDeNoiseMethod = RegressionServiceGrpc.getDeNoiseMethod) == null) {
          RegressionServiceGrpc.getDeNoiseMethod = getDeNoiseMethod =
              io.grpc.MethodDescriptor.<stubs.Service.TestReq, stubs.Service.deNoiseResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "DeNoise"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  stubs.Service.TestReq.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  stubs.Service.deNoiseResponse.getDefaultInstance()))
              .setSchemaDescriptor(new RegressionServiceMethodDescriptorSupplier("DeNoise"))
              .build();
        }
      }
    }
    return getDeNoiseMethod;
  }

  private static volatile io.grpc.MethodDescriptor<stubs.Service.TestReq,
      stubs.Service.testResponse> getTestMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "Test",
      requestType = stubs.Service.TestReq.class,
      responseType = stubs.Service.testResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<stubs.Service.TestReq,
      stubs.Service.testResponse> getTestMethod() {
    io.grpc.MethodDescriptor<stubs.Service.TestReq, stubs.Service.testResponse> getTestMethod;
    if ((getTestMethod = RegressionServiceGrpc.getTestMethod) == null) {
      synchronized (RegressionServiceGrpc.class) {
        if ((getTestMethod = RegressionServiceGrpc.getTestMethod) == null) {
          RegressionServiceGrpc.getTestMethod = getTestMethod =
              io.grpc.MethodDescriptor.<stubs.Service.TestReq, stubs.Service.testResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Test"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  stubs.Service.TestReq.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  stubs.Service.testResponse.getDefaultInstance()))
              .setSchemaDescriptor(new RegressionServiceMethodDescriptorSupplier("Test"))
              .build();
        }
      }
    }
    return getTestMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static RegressionServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<RegressionServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<RegressionServiceStub>() {
        @java.lang.Override
        public RegressionServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new RegressionServiceStub(channel, callOptions);
        }
      };
    return RegressionServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static RegressionServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<RegressionServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<RegressionServiceBlockingStub>() {
        @java.lang.Override
        public RegressionServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new RegressionServiceBlockingStub(channel, callOptions);
        }
      };
    return RegressionServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static RegressionServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<RegressionServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<RegressionServiceFutureStub>() {
        @java.lang.Override
        public RegressionServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new RegressionServiceFutureStub(channel, callOptions);
        }
      };
    return RegressionServiceFutureStub.newStub(factory, channel);
  }

  /**
   */
  public static abstract class RegressionServiceImplBase implements io.grpc.BindableService {

    /**
     */
    public void end(stubs.Service.endRequest request,
        io.grpc.stub.StreamObserver<stubs.Service.endResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getEndMethod(), responseObserver);
    }

    /**
     */
    public void start(stubs.Service.startRequest request,
        io.grpc.stub.StreamObserver<stubs.Service.startResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getStartMethod(), responseObserver);
    }

    /**
     */
    public void getTC(stubs.Service.getTCRequest request,
        io.grpc.stub.StreamObserver<stubs.Service.TestCase> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGetTCMethod(), responseObserver);
    }

    /**
     */
    public void getTCS(stubs.Service.getTCSRequest request,
        io.grpc.stub.StreamObserver<stubs.Service.getTCSResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGetTCSMethod(), responseObserver);
    }

    /**
     */
    public void postTC(stubs.Service.TestCaseReq request,
        io.grpc.stub.StreamObserver<stubs.Service.postTCResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getPostTCMethod(), responseObserver);
    }

    /**
     */
    public void deNoise(stubs.Service.TestReq request,
        io.grpc.stub.StreamObserver<stubs.Service.deNoiseResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getDeNoiseMethod(), responseObserver);
    }

    /**
     */
    public void test(stubs.Service.TestReq request,
        io.grpc.stub.StreamObserver<stubs.Service.testResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getTestMethod(), responseObserver);
    }

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return io.grpc.ServerServiceDefinition.builder(getServiceDescriptor())
          .addMethod(
            getEndMethod(),
            io.grpc.stub.ServerCalls.asyncUnaryCall(
              new MethodHandlers<
                stubs.Service.endRequest,
                stubs.Service.endResponse>(
                  this, METHODID_END)))
          .addMethod(
            getStartMethod(),
            io.grpc.stub.ServerCalls.asyncUnaryCall(
              new MethodHandlers<
                stubs.Service.startRequest,
                stubs.Service.startResponse>(
                  this, METHODID_START)))
          .addMethod(
            getGetTCMethod(),
            io.grpc.stub.ServerCalls.asyncUnaryCall(
              new MethodHandlers<
                stubs.Service.getTCRequest,
                stubs.Service.TestCase>(
                  this, METHODID_GET_TC)))
          .addMethod(
            getGetTCSMethod(),
            io.grpc.stub.ServerCalls.asyncUnaryCall(
              new MethodHandlers<
                stubs.Service.getTCSRequest,
                stubs.Service.getTCSResponse>(
                  this, METHODID_GET_TCS)))
          .addMethod(
            getPostTCMethod(),
            io.grpc.stub.ServerCalls.asyncUnaryCall(
              new MethodHandlers<
                stubs.Service.TestCaseReq,
                stubs.Service.postTCResponse>(
                  this, METHODID_POST_TC)))
          .addMethod(
            getDeNoiseMethod(),
            io.grpc.stub.ServerCalls.asyncUnaryCall(
              new MethodHandlers<
                stubs.Service.TestReq,
                stubs.Service.deNoiseResponse>(
                  this, METHODID_DE_NOISE)))
          .addMethod(
            getTestMethod(),
            io.grpc.stub.ServerCalls.asyncUnaryCall(
              new MethodHandlers<
                stubs.Service.TestReq,
                stubs.Service.testResponse>(
                  this, METHODID_TEST)))
          .build();
    }
  }

  /**
   */
  public static final class RegressionServiceStub extends io.grpc.stub.AbstractAsyncStub<RegressionServiceStub> {
    private RegressionServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected RegressionServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new RegressionServiceStub(channel, callOptions);
    }

    /**
     */
    public void end(stubs.Service.endRequest request,
        io.grpc.stub.StreamObserver<stubs.Service.endResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getEndMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void start(stubs.Service.startRequest request,
        io.grpc.stub.StreamObserver<stubs.Service.startResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getStartMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void getTC(stubs.Service.getTCRequest request,
        io.grpc.stub.StreamObserver<stubs.Service.TestCase> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getGetTCMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void getTCS(stubs.Service.getTCSRequest request,
        io.grpc.stub.StreamObserver<stubs.Service.getTCSResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getGetTCSMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void postTC(stubs.Service.TestCaseReq request,
        io.grpc.stub.StreamObserver<stubs.Service.postTCResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getPostTCMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void deNoise(stubs.Service.TestReq request,
        io.grpc.stub.StreamObserver<stubs.Service.deNoiseResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getDeNoiseMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void test(stubs.Service.TestReq request,
        io.grpc.stub.StreamObserver<stubs.Service.testResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getTestMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   */
  public static final class RegressionServiceBlockingStub extends io.grpc.stub.AbstractBlockingStub<RegressionServiceBlockingStub> {
    private RegressionServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected RegressionServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new RegressionServiceBlockingStub(channel, callOptions);
    }

    /**
     */
    public stubs.Service.endResponse end(stubs.Service.endRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getEndMethod(), getCallOptions(), request);
    }

    /**
     */
    public stubs.Service.startResponse start(stubs.Service.startRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getStartMethod(), getCallOptions(), request);
    }

    /**
     */
    public stubs.Service.TestCase getTC(stubs.Service.getTCRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getGetTCMethod(), getCallOptions(), request);
    }

    /**
     */
    public stubs.Service.getTCSResponse getTCS(stubs.Service.getTCSRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getGetTCSMethod(), getCallOptions(), request);
    }

    /**
     */
    public stubs.Service.postTCResponse postTC(stubs.Service.TestCaseReq request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getPostTCMethod(), getCallOptions(), request);
    }

    /**
     */
    public stubs.Service.deNoiseResponse deNoise(stubs.Service.TestReq request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getDeNoiseMethod(), getCallOptions(), request);
    }

    /**
     */
    public stubs.Service.testResponse test(stubs.Service.TestReq request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getTestMethod(), getCallOptions(), request);
    }
  }

  /**
   */
  public static final class RegressionServiceFutureStub extends io.grpc.stub.AbstractFutureStub<RegressionServiceFutureStub> {
    private RegressionServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected RegressionServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new RegressionServiceFutureStub(channel, callOptions);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<stubs.Service.endResponse> end(
        stubs.Service.endRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getEndMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<stubs.Service.startResponse> start(
        stubs.Service.startRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getStartMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<stubs.Service.TestCase> getTC(
        stubs.Service.getTCRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getGetTCMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<stubs.Service.getTCSResponse> getTCS(
        stubs.Service.getTCSRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getGetTCSMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<stubs.Service.postTCResponse> postTC(
        stubs.Service.TestCaseReq request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getPostTCMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<stubs.Service.deNoiseResponse> deNoise(
        stubs.Service.TestReq request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getDeNoiseMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<stubs.Service.testResponse> test(
        stubs.Service.TestReq request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getTestMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_END = 0;
  private static final int METHODID_START = 1;
  private static final int METHODID_GET_TC = 2;
  private static final int METHODID_GET_TCS = 3;
  private static final int METHODID_POST_TC = 4;
  private static final int METHODID_DE_NOISE = 5;
  private static final int METHODID_TEST = 6;

  private static final class MethodHandlers<Req, Resp> implements
      io.grpc.stub.ServerCalls.UnaryMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ServerStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ClientStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.BidiStreamingMethod<Req, Resp> {
    private final RegressionServiceImplBase serviceImpl;
    private final int methodId;

    MethodHandlers(RegressionServiceImplBase serviceImpl, int methodId) {
      this.serviceImpl = serviceImpl;
      this.methodId = methodId;
    }

    @java.lang.Override
    @java.lang.SuppressWarnings("unchecked")
    public void invoke(Req request, io.grpc.stub.StreamObserver<Resp> responseObserver) {
      switch (methodId) {
        case METHODID_END:
          serviceImpl.end((stubs.Service.endRequest) request,
              (io.grpc.stub.StreamObserver<stubs.Service.endResponse>) responseObserver);
          break;
        case METHODID_START:
          serviceImpl.start((stubs.Service.startRequest) request,
              (io.grpc.stub.StreamObserver<stubs.Service.startResponse>) responseObserver);
          break;
        case METHODID_GET_TC:
          serviceImpl.getTC((stubs.Service.getTCRequest) request,
              (io.grpc.stub.StreamObserver<stubs.Service.TestCase>) responseObserver);
          break;
        case METHODID_GET_TCS:
          serviceImpl.getTCS((stubs.Service.getTCSRequest) request,
              (io.grpc.stub.StreamObserver<stubs.Service.getTCSResponse>) responseObserver);
          break;
        case METHODID_POST_TC:
          serviceImpl.postTC((stubs.Service.TestCaseReq) request,
              (io.grpc.stub.StreamObserver<stubs.Service.postTCResponse>) responseObserver);
          break;
        case METHODID_DE_NOISE:
          serviceImpl.deNoise((stubs.Service.TestReq) request,
              (io.grpc.stub.StreamObserver<stubs.Service.deNoiseResponse>) responseObserver);
          break;
        case METHODID_TEST:
          serviceImpl.test((stubs.Service.TestReq) request,
              (io.grpc.stub.StreamObserver<stubs.Service.testResponse>) responseObserver);
          break;
        default:
          throw new AssertionError();
      }
    }

    @java.lang.Override
    @java.lang.SuppressWarnings("unchecked")
    public io.grpc.stub.StreamObserver<Req> invoke(
        io.grpc.stub.StreamObserver<Resp> responseObserver) {
      switch (methodId) {
        default:
          throw new AssertionError();
      }
    }
  }

  private static abstract class RegressionServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    RegressionServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return stubs.Service.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("RegressionService");
    }
  }

  private static final class RegressionServiceFileDescriptorSupplier
      extends RegressionServiceBaseDescriptorSupplier {
    RegressionServiceFileDescriptorSupplier() {}
  }

  private static final class RegressionServiceMethodDescriptorSupplier
      extends RegressionServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final String methodName;

    RegressionServiceMethodDescriptorSupplier(String methodName) {
      this.methodName = methodName;
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.MethodDescriptor getMethodDescriptor() {
      return getServiceDescriptor().findMethodByName(methodName);
    }
  }

  private static volatile io.grpc.ServiceDescriptor serviceDescriptor;

  public static io.grpc.ServiceDescriptor getServiceDescriptor() {
    io.grpc.ServiceDescriptor result = serviceDescriptor;
    if (result == null) {
      synchronized (RegressionServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new RegressionServiceFileDescriptorSupplier())
              .addMethod(getEndMethod())
              .addMethod(getStartMethod())
              .addMethod(getGetTCMethod())
              .addMethod(getGetTCSMethod())
              .addMethod(getPostTCMethod())
              .addMethod(getDeNoiseMethod())
              .addMethod(getTestMethod())
              .build();
        }
      }
    }
    return result;
  }
}
