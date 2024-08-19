package io.grpc.binder;

import static android.content.Intent.ACTION_PACKAGE_REPLACED;
import static android.content.Intent.URI_INTENT_SCHEME;
import static android.os.Looper.getMainLooper;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import androidx.core.content.ContextCompat;
import androidx.test.core.app.ApplicationProvider;
import io.grpc.EquivalentAddressGroup;
import io.grpc.NameResolver;
import io.grpc.NameResolver.ResolutionResult;
import io.grpc.NameResolver.ServiceConfigParser;
import io.grpc.NameResolverProvider;
import io.grpc.Status;
import io.grpc.SynchronizationContext;
import java.net.URI;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoTestRule;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.shadows.ShadowPackageManager;

/** A test for IntentNameResolverProvider. */
@RunWith(RobolectricTestRunner.class)
public final class IntentNameResolverProviderTest {

  private static final ComponentName SOME_COMPONENT_NAME =
      new ComponentName("com.foo.bar", "SomeComponent");
  private static final ComponentName ANOTHER_COMPONENT_NAME =
      new ComponentName("org.blah", "AnotherComponent");
  private final Application appContext = ApplicationProvider.getApplicationContext();
  private final NameResolver.Args args = newNameResolverArgs();

  private final ShadowPackageManager shadowPackageManager =
      shadowOf(appContext.getPackageManager());
  private NameResolverProvider provider;

  @Rule public MockitoTestRule mockitoTestRule = MockitoJUnit.testRule(this);
  @Mock public NameResolver.Listener2 mockListener;
  @Captor public ArgumentCaptor<ResolutionResult> resultCaptor;

  @Before
  public void setUp() {
    provider = new IntentNameResolverProvider(appContext);
  }

  @Test
  public void testProviderScheme_returnsIntentScheme() throws Exception {
    assertThat(provider.getDefaultScheme())
        .isEqualTo(IntentNameResolverProvider.ANDROID_INTENT_SCHEME);
  }

  @Test
  public void testNoResolverForUnknownScheme_returnsNull() throws Exception {
    assertThat(provider.newNameResolver(new URI("random://uri"), args)).isNull();
  }

  @Test
  public void testResolverForIntentScheme_returnsResolverWithLocalHostAuthority() throws Exception {
    URI uri = getIntentUri(newIntent());
    NameResolver resolver = provider.newNameResolver(uri, args);
    assertThat(resolver).isNotNull();
    assertThat(resolver.getServiceAuthority()).isEqualTo("localhost");
  }

  @Test
  public void testResolutionWithoutServicesAvailable_returnsUnimplemented() throws Exception {
    Status resolutionError = resolveExpectingError(getIntentUri(newIntent()));
    assertThat(resolutionError).isNotNull();
    assertThat(resolutionError.getCode()).isEqualTo(Status.UNIMPLEMENTED.getCode());
  }

  @Test
  public void testResolutionWithBadUri_returnsIllegalArg() throws Exception {
    Status resolutionError = resolveExpectingError(new URI("intent:xxx#Intent;e.x=1;end;"));
    assertThat(resolutionError.getCode()).isEqualTo(Status.INVALID_ARGUMENT.getCode());
  }

  @Test
  public void testResolutionWithMultipleServicesAvailable_returnsAndroidComponentAddresses()
      throws Exception {
    Intent intent = newIntent();
    IntentFilter serviceIntentFilter = newFilterMatching(intent);

    shadowPackageManager.addServiceIfNotPresent(SOME_COMPONENT_NAME);
    shadowPackageManager.addIntentFilterForService(SOME_COMPONENT_NAME, serviceIntentFilter);

    // Adds another valid Service
    shadowPackageManager.addServiceIfNotPresent(ANOTHER_COMPONENT_NAME);
    shadowPackageManager.addIntentFilterForService(ANOTHER_COMPONENT_NAME, serviceIntentFilter);

    NameResolver nameResolver = provider.newNameResolver(getIntentUri(intent), args);
    nameResolver.start(mockListener);
    shadowOf(getMainLooper()).idle();

    verify(mockListener, never()).onError(any());
    verify(mockListener).onResult(resultCaptor.capture());
    assertThat(resultCaptor.getValue().getAddresses())
        .containsExactly(
            new EquivalentAddressGroup(
                AndroidComponentAddress.forBindIntent(
                    intent.cloneFilter().setComponent(SOME_COMPONENT_NAME))),
            new EquivalentAddressGroup(
                AndroidComponentAddress.forBindIntent(
                    intent.cloneFilter().setComponent(ANOTHER_COMPONENT_NAME))));

    nameResolver.shutdown();
    shadowOf(getMainLooper()).idle();
  }

  @Test
  public void testServiceRemoved_pushesUpdatedAndroidComponentAddresses() throws Exception {
    Intent intent = newIntent();
    IntentFilter serviceIntentFilter = newFilterMatching(intent);

    shadowPackageManager.addServiceIfNotPresent(SOME_COMPONENT_NAME);
    shadowPackageManager.addIntentFilterForService(SOME_COMPONENT_NAME, serviceIntentFilter);

    // Adds another valid Service
    shadowPackageManager.addServiceIfNotPresent(ANOTHER_COMPONENT_NAME);
    shadowPackageManager.addIntentFilterForService(ANOTHER_COMPONENT_NAME, serviceIntentFilter);

    NameResolver nameResolver = provider.newNameResolver(getIntentUri(intent), args);
    nameResolver.start(mockListener);
    shadowOf(getMainLooper()).idle();

    verify(mockListener, never()).onError(any());
    verify(mockListener).onResult(resultCaptor.capture());
    assertThat(resultCaptor.getValue().getAddresses())
        .containsExactly(
            new EquivalentAddressGroup(
                AndroidComponentAddress.forBindIntent(
                    intent.cloneFilter().setComponent(SOME_COMPONENT_NAME))),
            new EquivalentAddressGroup(
                AndroidComponentAddress.forBindIntent(
                    intent.cloneFilter().setComponent(ANOTHER_COMPONENT_NAME))));

    shadowPackageManager.removeService(ANOTHER_COMPONENT_NAME);
    broadcastPackageChange(ACTION_PACKAGE_REPLACED, ANOTHER_COMPONENT_NAME.getPackageName());
    shadowOf(getMainLooper()).idle();

    verify(mockListener, never()).onError(any());
    verify(mockListener, times(2)).onResult(resultCaptor.capture());
    assertThat(resultCaptor.getValue().getAddresses())
        .containsExactly(
            new EquivalentAddressGroup(
                AndroidComponentAddress.forBindIntent(
                    intent.cloneFilter().setComponent(SOME_COMPONENT_NAME))));

    nameResolver.shutdown();
    shadowOf(getMainLooper()).idle();

    // No Listener callbacks post-shutdown().
    verifyNoMoreInteractions(mockListener);
    // No leaked receivers.
    assertThat(shadowOf(appContext).getRegisteredReceivers()).isEmpty();
  }

  @Test
  public void testRefresh_returnsSameAndroidComponentAddresses() throws Exception {
    Intent intent = newIntent();
    IntentFilter serviceIntentFilter = newFilterMatching(intent);

    shadowPackageManager.addServiceIfNotPresent(SOME_COMPONENT_NAME);
    shadowPackageManager.addIntentFilterForService(SOME_COMPONENT_NAME, serviceIntentFilter);

    // Adds another valid Service
    shadowPackageManager.addServiceIfNotPresent(ANOTHER_COMPONENT_NAME);
    shadowPackageManager.addIntentFilterForService(ANOTHER_COMPONENT_NAME, serviceIntentFilter);

    NameResolver nameResolver = provider.newNameResolver(getIntentUri(intent), args);
    nameResolver.start(mockListener);
    shadowOf(getMainLooper()).idle();

    verify(mockListener, never()).onError(any());
    verify(mockListener).onResult(resultCaptor.capture());
    assertThat(resultCaptor.getValue().getAddresses())
        .containsExactly(
            new EquivalentAddressGroup(
                AndroidComponentAddress.forBindIntent(
                    intent.cloneFilter().setComponent(SOME_COMPONENT_NAME))),
            new EquivalentAddressGroup(
                AndroidComponentAddress.forBindIntent(
                    intent.cloneFilter().setComponent(ANOTHER_COMPONENT_NAME))));

    nameResolver.refresh();
    shadowOf(getMainLooper()).idle();
    verify(mockListener, never()).onError(any());
    verify(mockListener, times(2)).onResult(resultCaptor.capture());
    assertThat(resultCaptor.getValue().getAddresses())
        .containsExactly(
            new EquivalentAddressGroup(
                AndroidComponentAddress.forBindIntent(
                    intent.cloneFilter().setComponent(SOME_COMPONENT_NAME))),
            new EquivalentAddressGroup(
                AndroidComponentAddress.forBindIntent(
                    intent.cloneFilter().setComponent(ANOTHER_COMPONENT_NAME))));

    nameResolver.shutdown();
    shadowOf(getMainLooper()).idle();
    assertThat(shadowOf(appContext).getRegisteredReceivers()).isEmpty();
  }

  private void broadcastPackageChange(String action, String pkgName) {
    Intent broadcast = new Intent();
    broadcast.setAction(action);
    broadcast.setData(Uri.parse("package:" + pkgName));
    appContext.sendBroadcast(broadcast);
  }

  @Test
  public void testResolutionOnResultThrows_onErrorNotCalled() throws Exception {
    Intent intent = newIntent();
    shadowPackageManager.addServiceIfNotPresent(SOME_COMPONENT_NAME);
    shadowPackageManager.addIntentFilterForService(SOME_COMPONENT_NAME, newFilterMatching(intent));

    doThrow(SomeRuntimeException.class).when(mockListener).onResult(any());

    provider.newNameResolver(getIntentUri(intent), args).start(mockListener);
    try {
      shadowOf(getMainLooper()).idle();
    } catch (SomeRuntimeException e) {
      // Permitted.
    }

    verify(mockListener).onResult(any());
    verify(mockListener, never()).onError(any());
  }

  final class SomeRuntimeException extends RuntimeException {
    static final long serialVersionUID = 0;
  }

  private Status resolveExpectingError(URI target) throws Exception {
    AtomicReference<Status> statusResult = new AtomicReference<>();
    AtomicReference<ResolutionResult> resolutionResult = new AtomicReference<>();
    provider
        .newNameResolver(target, args)
        .start(
            new NameResolver.Listener2() {
              @Override
              public void onResult(ResolutionResult result) {
                resolutionResult.set(result);
              }

              @Override
              public void onError(Status status) {
                statusResult.set(status);
              }
            });
    if (resolutionResult.get() != null) {
      Assert.fail("Expected error, but got result: " + resolutionResult.get());
    }
    shadowOf(getMainLooper()).idle();
    return checkNotNull(statusResult.get());
  }

  private static Intent newIntent() throws Exception {
    Intent intent = new Intent();
    intent.setAction("test.action");
    intent.setData(Uri.parse("grpc:ServiceName"));
    return intent;
  }

  private static IntentFilter newFilterMatching(Intent intent) throws Exception {
    IntentFilter filter = new IntentFilter();
    if (intent.getAction() != null) {
      filter.addAction(intent.getAction());
    }
    Uri data = intent.getData();
    if (data != null) {
      if (data.getScheme() != null) {
        filter.addDataScheme(data.getScheme());
      }
      if (data.getSchemeSpecificPart() != null) {
        filter.addDataSchemeSpecificPart(data.getSchemeSpecificPart(), 0);
      }
    }
    Set<String> categories = intent.getCategories();
    if (categories != null) {
      for (String category : categories) {
        filter.addCategory(category);
      }
    }
    return filter;
  }

  private static URI getIntentUri(Intent intent) throws Exception {
    return new URI(intent.toUri(URI_INTENT_SCHEME));
  }

  /** Returns a new test-specific {@link NameResolver.Args} instance. */
  private NameResolver.Args newNameResolverArgs() {
    return NameResolver.Args.newBuilder()
        .setDefaultPort(-1)
        .setProxyDetector((target) -> null) // No proxies here.
        .setSynchronizationContext(synchronizationContext())
        .setOffloadExecutor(ContextCompat.getMainExecutor(appContext))
        .setServiceConfigParser(mock(ServiceConfigParser.class))
        .build();
    }

    /**
     * Returns a test {@link SynchronizationContext}.
     *
     * <p>Exceptions will cause the test to fail with {@link AssertionError}.
     */
    private static SynchronizationContext synchronizationContext() {
      return new SynchronizationContext(
          (thread, exception) -> {
            throw new AssertionError(exception);
          });
    }
}
