/**
 * 
 */
package org.jocean.xharbor.relay.impl;

/**
 * @author isdom
 *
 */
public interface TimeInterval10ms_100ms_500ms_1s_5sMXBean {
	public	int	get1_lt10ms();
	public	int	get2_lt100ms();
	public	int	get3_lt500ms();
	public	int	get4_lt1s();
	public	int	get5_lt5s();
	public	int	get6_mt5s();
}
