package br.fml.eti.machinegun;

import br.fml.eti.behavior.BuildingException;
import br.fml.eti.behavior.Factory;
import br.fml.eti.machinegun.auditorship.ArmyAudit;

/**
 * A {@link Target} groups all the information needed to a bullet (data)
 * reaches your right destination (be processed in the right way).
 *
 * @author Felipe Micaroni Lalli (micaroni@gmail.com)
 *         Nov 15, 2010 1:51:29 PM
 */
public class Target<T> {
    private String queueName;
    private Factory<DirtyWork<T>> dirtyWorkFactory;

    public Target(String queueName, Factory<DirtyWork<T>> dirtyWorkFactory) {
        this.queueName = queueName;
        this.dirtyWorkFactory = dirtyWorkFactory;
    }

    public String getQueueName() {
        return queueName;
    }

    public void workOnIt(long idJob, String soldier, ArmyAudit audit, T data)
            throws BuildingException {

        dirtyWorkFactory.buildANewInstance().workOnIt(idJob, soldier, audit, data);
    }
}

