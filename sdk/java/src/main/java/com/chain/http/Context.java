package com.chain.http;

import com.chain.exception.*;

import java.io.IOException;
import java.lang.reflect.Type;
import java.net.*;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.List;

import com.google.gson.Gson;

import com.squareup.okhttp.CertificatePinner;
import com.squareup.okhttp.Credentials;
import com.squareup.okhttp.MediaType;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.RequestBody;
import com.squareup.okhttp.Response;

/**
 * The Context object contains all information necessary to
 * perform an HTTP request against a remote API.
 */
public class Context {

  private URL url;
  private String accessToken;
  private OkHttpClient httpClient;

  /**
   * Create a new http Context object using the default development host URL.
   */
  public Context() {
    URL url;
    try {
      url = new URL("http://localhost:1999");
    } catch (Exception e) {
      throw new RuntimeException("invalid default development URL");
    }

    this.url = url;
    this.httpClient = new OkHttpClient();
    this.httpClient.setFollowRedirects(false);
  }

  /**
   * Create a new http Context object
   *
   * @param url The URL of the Chain Core or HSM
   */
  public Context(URL url) {
    this.url = url;
    this.httpClient = new OkHttpClient();
    this.httpClient.setFollowRedirects(false);
  }

  /**
   * Create a new http Context object
   *
   * @param url The URL of the Chain Core or HSM
   * @param accessToken A Client API access token.
   */
  public Context(URL url, String accessToken) {
    this(url);
    this.accessToken = accessToken;
  }

  /**
   * Perform a single HTTP POST request against the API for a specific action.
   *
   * @param action The requested API action
   * @param body Body payload sent to the API as JSON
   * @param tClass Type of object to be deserialized from the repsonse JSON
   * @return the result of the post request
   * @throws ChainException
   */
  public <T> T request(String action, Object body, Type tClass) throws ChainException {
    return post(
        action,
        body,
        (Response response, Gson deserializer) -> {
          return deserializer.fromJson(response.body().charStream(), tClass);
        });
  }

  /**
   * Perform a single HTTP POST request against the API for a specific action.
   * Use this method if you want batch semantics, i.e., the endpoint response
   * is an array of valid objects interleaved with arrays, once corresponding to
   * each input object.
   *
   * @param action The requested API action
   * @param body Body payload sent to the API as JSON
   * @param tClass Type of object to be deserialized from the response JSON
   * @return the result of the post request
   * @throws ChainException
   */
  public <T> BatchResponse<T> batchRequest(String action, Object body, Type tClass)
      throws ChainException {
    return post(
        action,
        body,
        (Response response, Gson deserializer) ->
            new BatchResponse(response, deserializer, tClass));
  }

  /**
   * Perform a single HTTP POST request against the API for a specific action.
   * Use this method if you want single-item semantics (creating single assets,
   * building single transactions) but the API endpoint is implemented as a
   * batch call.
   *
   * Because request bodies for batch calls do not share a consistent format,
   * this method does not perform any automatic arrayification of outgoing
   * parameters. Remember to arrayify your request objects where appropriate.
   *
   * @param action The requested API action
   * @param body Body payload sent to the API as JSON
   * @param tClass Type of object to be deserialized from the repsonse JSON
   * @return the result of the post request
   * @throws ChainException
   */
  public <T> T singletonBatchRequest(String action, Object body, Type tClass)
      throws ChainException {
    return post(
        action,
        body,
        (Response response, Gson deserializer) -> {
          BatchResponse<T> batch = new BatchResponse(response, deserializer, tClass);

          List<APIException> errors = batch.errors();
          if (errors.size() == 1) {
            // This throw must occur within this lambda in order for APIClient's
            // retry logic to take effect.
            throw errors.get(0);
          }

          List<T> successes = batch.successes();
          if (successes.size() == 1) {
            return successes.get(0);
          }

          // We should never get here, unless there is a bug in either the SDK or
          // API code, causing a non-singleton response.
          throw new ChainException(
              "Invalid singleton repsonse, request ID "
                  + batch.response().headers().get("Chain-Request-ID"));
        });
  }

  public URL url() {
    return this.url;
  }

  public boolean hasAccessToken() {
    return this.accessToken != null && !this.accessToken.isEmpty();
  }

  public String accessToken() {
    return accessToken;
  }

  /**
   * Specifies the MIME type for HTTP requests.
   */
  public static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
  /**
   * Serializer object used to serialize/deserialize json requests/responses.
   */
  public static final Gson serializer = new Gson();

  /**
   * Pins a public key to the HTTP client.
   * @param provider certificate provider
   * @param subjPubKeyInfoHash public key hash
   */
  public void pinCertificate(String provider, String subjPubKeyInfoHash) {
    CertificatePinner cp =
        new CertificatePinner.Builder().add(provider, subjPubKeyInfoHash).build();
    this.httpClient.setCertificatePinner(cp);
  }

  /**
   * Sets the default connect timeout for new connections. A value of 0 means no timeout.
   * @param timeout the number of time units for the default timeout
   * @param unit the unit of time
   */
  public void setConnectTimeout(long timeout, TimeUnit unit) {
    this.httpClient.setConnectTimeout(timeout, unit);
  }

  /**
   * Sets the default read timeout for new connections. A value of 0 means no timeout.
   * @param timeout the number of time units for the default timeout
   * @param unit the unit of time
   */
  public void setReadTimeout(long timeout, TimeUnit unit) {
    this.httpClient.setReadTimeout(timeout, unit);
  }

  /**
   * Sets the default write timeout for new connections. A value of 0 means no timeout.
   * @param timeout the number of time units for the default timeout
   * @param unit the unit of time
   */
  public void setWriteTimeout(long timeout, TimeUnit unit) {
    this.httpClient.setWriteTimeout(timeout, unit);
  }

  /**
   * Sets the proxy information for the HTTP client.
   * @param proxy proxy object
   */
  public void setProxy(Proxy proxy) {
    this.httpClient.setProxy(proxy);
  }

  public interface ResponseCreator<T> {
    T create(Response response, Gson deserializer) throws ChainException, IOException;
  }

  public <T> T post(String path, Object body, ResponseCreator<T> respCreator)
      throws ChainException {
    RequestBody requestBody = RequestBody.create(this.JSON, serializer.toJson(body));
    Request req;

    try {
      Request.Builder builder =
          new Request.Builder()
              // TODO: include version string in User-Agent when available
              .header("User-Agent", "chain-sdk-java")
              .url(this.createEndpoint(path))
              .method("POST", requestBody);
      if (hasAccessToken()) {
        builder = builder.header("Authorization", buildCredentials());
      }
      req = builder.build();
    } catch (MalformedURLException ex) {
      throw new BadURLException(ex.getMessage());
    }

    ChainException exception = null;
    for (int attempt = 1; attempt - 1 <= MAX_RETRIES; attempt++) {
      // Wait between retrys. The first attempt will not wait at all.
      if (attempt > 1) {
        int delayMillis = retryDelayMillis(attempt - 1);
        try {
          TimeUnit.MILLISECONDS.sleep(delayMillis);
        } catch (InterruptedException e) {
        }
      }

      try {
        Response resp = this.checkError(this.httpClient.newCall(req).execute());
        return respCreator.create(resp, serializer);
      } catch (IOException ex) {
        // The OkHttp library already performs retries for most
        // I/O-related errors. We can add retries here too if this
        // becomes a problem.
        throw new HTTPException(ex.getMessage());
      } catch (ConnectivityException ex) {
        // ConnectivityExceptions are always retriable.
        exception = ex;
      } catch (APIException ex) {
        // Check if this error is retriable (either it's a status code that's
        // always retriable or the error is explicitly marked as temporary.
        if (!isRetriableStatusCode(ex.statusCode) && !ex.temporary) {
          throw ex;
        }
        exception = ex;
      }
    }
    throw exception;
  }

  private static final Random randomGenerator = new Random();
  private static final int MAX_RETRIES = 10;
  private static final int RETRY_BASE_DELAY_MILLIS = 40;
  private static final int RETRY_MAX_DELAY_MILLIS = 4000;

  private static int retryDelayMillis(int retryAttempt) {
    // Calculate the max delay as base * 2 ^ (retryAttempt - 1).
    int max = RETRY_BASE_DELAY_MILLIS * (1 << (retryAttempt - 1));
    max = Math.min(max, RETRY_MAX_DELAY_MILLIS);

    // To incorporate jitter, use a pseudorandom delay between [1, max] millis.
    return randomGenerator.nextInt(max) + 1;
  }

  private static final int[] RETRIABLE_STATUS_CODES = {
    408, // Request Timeout
    429, // Too Many Requests
    500, // Internal Server Error
    502, // Bad Gateway
    503, // Service Unavailable
    504, // Gateway Timeout
    509, // Bandwidth Limit Exceeded
  };

  private static boolean isRetriableStatusCode(int statusCode) {
    for (int i = 0; i < RETRIABLE_STATUS_CODES.length; i++) {
      if (RETRIABLE_STATUS_CODES[i] == statusCode) {
        return true;
      }
    }
    return false;
  }

  private Response checkError(Response response) throws ChainException {
    String rid = response.headers().get("Chain-Request-ID");
    if (rid == null || rid.length() == 0) {
      // Header field Chain-Request-ID is set by the backend
      // API server. If this field is set, then we can expect
      // the body to be well-formed JSON. If it's not set,
      // then we are probably talking to a gateway or proxy.
      throw new ConnectivityException(response);
    }

    if ((response.code() / 100) != 2) {
      try {
        APIException err = serializer.fromJson(response.body().charStream(), APIException.class);
        if (err.code != null) {
          err.requestId = rid;
          err.statusCode = response.code();
          throw err;
        }
      } catch (IOException ex) {
        throw new JSONException("Unable to read body. " + ex.getMessage(), rid);
      }
    }
    return response;
  }

  private URL createEndpoint(String path) throws MalformedURLException {
    try {
      URI u = new URI(this.url.toString() + "/" + path);
      u = u.normalize();
      return new URL(u.toString());
    } catch (URISyntaxException e) {
      throw new MalformedURLException();
    }
  }

  private String buildCredentials() {
    String user = "";
    String pass = "";
    if (accessToken != null) {
      String[] parts = accessToken.split(":");
      if (parts.length >= 1) {
        user = parts[0];
      }
      if (parts.length >= 2) {
        pass = parts[1];
      }
    }
    return Credentials.basic(user, pass);
  }

  private String identifier() {
    if (this.hasAccessToken()) {
      return String.format(
          "%s://%s@%s", this.url.getProtocol(), this.accessToken, this.url.getAuthority());
    }
    return String.format("%s://%s", this.url.getProtocol(), this.url.getAuthority());
  }

  @Override
  public int hashCode() {
    return identifier().hashCode();
  }

  @Override
  public boolean equals(Object o) {
    if (o == null) return false;
    if (!(o instanceof Context)) return false;

    Context other = (Context) o;
    return this.identifier().equals(other.identifier());
  }
}
