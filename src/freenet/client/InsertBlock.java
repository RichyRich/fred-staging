/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client;

import com.db4o.ObjectContainer;

import freenet.keys.FreenetURI;
import freenet.support.api.Bucket;

/**
 * Class to contain everything needed for an insert.
 */
public class InsertBlock {

	private final Bucket data;
	private boolean isFreed;
	public final FreenetURI desiredURI;
	public final ClientMetadata clientMetadata;
	
	public InsertBlock(Bucket data, ClientMetadata metadata, FreenetURI desiredURI) {
		if(data == null) throw new NullPointerException();
		this.data = data;
		this.isFreed = false;
		if(metadata == null)
			clientMetadata = new ClientMetadata();
		else
			clientMetadata = metadata;
		this.desiredURI = desiredURI;
	}
	
	public Bucket getData() {
		return (isFreed ? null : data);
	}
	
	public void free(ObjectContainer container){
		synchronized (this) {
			if(isFreed) return;
			isFreed = true;
		}
		data.free();
		if(container != null) {
			data.removeFrom(container);
			container.delete(this);
		}
	}
	
	public void objectOnActivate(ObjectContainer container) {
		// Cascading activation of dependancies
		container.activate(data, 1); // will cascade
		container.activate(desiredURI, 5);
	}

}
