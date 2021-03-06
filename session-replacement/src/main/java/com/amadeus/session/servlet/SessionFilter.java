package com.amadeus.session.servlet;

import static com.amadeus.session.servlet.SessionHelpersFacade.commitRequest;
import static com.amadeus.session.servlet.SessionHelpersFacade.initSessionManagement;
import static com.amadeus.session.servlet.SessionHelpersFacade.initSessionManagementReset;
import static com.amadeus.session.servlet.SessionHelpersFacade.prepareRequest;
import static com.amadeus.session.servlet.SessionHelpersFacade.prepareResponse;

import java.io.IOException;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletRequestWrapper;
import javax.servlet.ServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.amadeus.session.ErrorTracker;
import com.amadeus.session.ResetManager;
import com.amadeus.session.SessionManager;

import redis.clients.jedis.exceptions.JedisException;

/**
 * Filter that wraps the httpRequest to enable Http Session caching.
 *
 * Note that it won't wrap the request twice even if the filter is called two times.
 *
 * @see ServletRequestWrapper
 */
public class SessionFilter implements Filter {
  ServletContext servletContext;

  private static final Logger logger = LoggerFactory.getLogger(SessionFilter.class);

  /**
   * Initializes session management based on repository for current servlet context.
   *
   * @param config
   *          The filter configuration.
   */
  @Override
  public void init(FilterConfig config) {
    logger.warn("init");
    initForSession(config);
  }

  /**
   * Initializes session management based on repository for current servlet context. This method is internal method for
   * session management.
   *
   * @param config
   *          The filter configuration.
   */
  public void initForSession(FilterConfig config) {
    if (servletContext == null) {
      servletContext = config.getServletContext();
      initSessionManagement(servletContext);
    }
  }

  /**
   * Implements wrapping of HTTP request and enables handling of sessions based on repository.
   *
   * @param originalRequest
   *          The request to wrap
   * @param originalResponse
   *          The response.
   * @param chain
   *          The filter chain.
   * @throws IOException
   *           If such exception occurs in chained filters.
   * @throws ServletException
   *           If such exception occurs in chained filters.
   */
  @Override
  public void doFilter(ServletRequest originalRequest, ServletResponse originalResponse, FilterChain chain)
      throws IOException, ServletException {
    ServletRequest request = prepareRequest(originalRequest, originalResponse, servletContext);
    ServletResponse response = prepareResponse(request, originalResponse, servletContext);
    SessionManager sessionManager = (SessionManager)servletContext.getAttribute(Attributes.SESSION_MANAGER);

    try {
      // Call next filter in chain
      chain.doFilter(request, response);
    } finally {
      try {
        commitRequest(request, originalRequest, servletContext);
      } catch (Exception e) {
        logger.error("Error into commit doFilter", e);
        ext(sessionManager, e);
        throw e;
      }
    }
  }

  private void ext(SessionManager sessionManager, Exception e) {
    if (e instanceof JedisException || (e.getClass().getName().indexOf("jedis.exceptions") != -1)) {

      ResetManager resetManager = (ResetManager)servletContext.getAttribute(Attributes.ResetManager);
      SessionManager sessionManagercurrent = (SessionManager)servletContext.getAttribute(Attributes.SESSION_MANAGER);
      if (sessionManagercurrent == sessionManager) {

        ErrorTracker errorTracker = resetManager.getErrorTracker();
        errorTracker.addError(System.currentTimeMillis());
        if (errorTracker.reachLimits()) {

          boolean lock = resetManager.tryLock();
          if (lock) {
            try {
              if (sessionManagercurrent != null) {
                sessionManagercurrent.reset();
              }
              initSessionManagementReset(servletContext, true);
            } finally {
              resetManager.unlock();
            }
          } else {
            logger.warn("already lokked");
          }
        } else {
          logger.warn("Error into redis but the limits is not reach:" + errorTracker.size());
        }
      }
    }
  }

  /**
   * No specific processing is done when this filter is being taken out of service.
   */
  @Override
  public void destroy() {
    // Do nothing
  }
}
