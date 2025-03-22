package io.grpc.binder.internal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.grpc.Attributes;
import io.grpc.Status;
import java.io.InputStream;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class MultiMessageClientStreamTest {

  @Mock private Inbound.ClientInbound mockInbound;
  @Mock private Outbound.ClientOutbound mockOutbound;

  private MultiMessageClientStream stream;

  @Before
  public void setUp() {
    stream = new MultiMessageClientStream(mockInbound, mockOutbound, Attributes.EMPTY);
  }

  @Test
  public void testRuntimeExceptionOnStartClosesTransport() {
    RuntimeException e = new RuntimeException("Test exception");
    // Simulate a RuntimeException during start()
    try {
      when(mockOutbound.onPrefixReady()).thenThrow(e);
      stream.start(mock(io.grpc.internal.ClientStreamListener.class));
    } catch (Exception ex) {
      // Ignore, we are testing the side effect on the transport.
    }

    // Verify that the transport's shutdownInternal method was called
    verify(mockInbound).closeAbnormal(Status.fromThrowable(e));
  }

  @Test
  public void testRuntimeExceptionOnWriteMessageClosesTransport() {
    RuntimeException e = new RuntimeException("Test exception");
    // Simulate a RuntimeException during writeMessage()
    try {
      when(mockOutbound.addMessage(any(InputStream.class))).thenThrow(e);
      stream.writeMessage(mock(InputStream.class));
    } catch (Exception ex) {
      // Ignore, we are testing the side effect on the transport.
    }

    // Verify that the transport's shutdownInternal method was called
    verify(mockInbound).closeAbnormal(Status.fromThrowable(e));
  }

  @Test
  public void testRuntimeExceptionOnHalfCloseClosesTransport() {
    RuntimeException e = new RuntimeException("Test exception");
    // Simulate a RuntimeException during halfClose()
    try {
      when(mockOutbound.sendHalfClose()).thenThrow(e);
      stream.halfClose();
    } catch (Exception ex) {
      // Ignore, we are testing the side effect on the transport.
    }

    // Verify that the transport's shutdownInternal method was called
    verify(mockInbound).closeAbnormal(Status.fromThrowable(e));
  }
}