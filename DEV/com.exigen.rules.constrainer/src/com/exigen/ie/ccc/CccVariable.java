package com.exigen.ie.ccc;

///////////////////////////////////////////////////////////////////////////////
/*
 * Copyright Exigen Group 1998, 1999, 2000, 2002
 * 320 Amboy Ave., Metuchen, NJ, 08840, USA, www.exigengroup.com
 *
 * The copyright to the computer program(s) herein
 * is the property of Exigen Group, USA. All rights reserved.
 * The program(s) may be used and/or copied only with
 * the written permission of Exigen Group
 * or in accordance with the terms and conditions
 * stipulated in the agreement/contract under which
 * the program(s) have been supplied.
 */
///////////////////////////////////////////////////////////////////////////////

/**
 * Base class for constrained variables
 * @see CccInteger CccFloat
 */

public abstract class CccVariable extends CccObject
{
	/**
	 * CccVariable constructor comment.
	 */
	public CccVariable(CccCore core, int type, String name)
	{
		super(core, name);
		setType(type | TM_VARIABLE);
	}

	public abstract void fetchConstrainerState();

	public abstract String value();

	public String toString()
	{
		return (name() + value());
	}

	public abstract CccGoal getMinimizeGoal();
	public abstract CccGoal getMaximizeGoal();

	public abstract String debugInfo();
}
