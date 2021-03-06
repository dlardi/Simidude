/*
 * Copyright by AGYNAMIX(R). All rights reserved. 
 * This file is made available under the terms of the
 * license this product is released under.
 * 
 * For details please see the license file you should have
 * received, or go to:
 * 
 * http://www.agynamix.com
 * 
 * Contributors: agynamix.com (http://www.agynamix.com)
 */
package com.agynamix.platform.infra;

public class PlatformException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public PlatformException(String msg)
  {
    super(msg);
  }

  public PlatformException(Throwable cause)
  {
    super(cause);
  }

  public PlatformException(String msg, Throwable cause)
  {
    super(msg, cause);
  }

}
