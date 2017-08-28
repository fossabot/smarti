package io.redlink.smarti.services;

import io.redlink.smarti.model.Client;
import io.redlink.smarti.model.config.Configuration;
import io.redlink.smarti.repositories.ClientRepository;
import org.bson.types.ObjectId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

/**
 * @author Thomas Kurz (thomas.kurz@redlink.co)
 * @since 17.08.17.
 */
@Service
public class ClientService {

    @Autowired
    ClientRepository clientRepository;

    @Autowired
    ConfigurationService configurationService;

    private static final String NAME_PATTERN = "[a-z0-9-_\\.:]+";

    public Iterable<Client> list() {
        return clientRepository.findAll();
    }

    public Client get(ObjectId id) {
        return clientRepository.findOne(id);
    }

    public Client save(Client client) {

        if(client.getId() != null) {
            Client client_old = clientRepository.findOne(client.getId());

            if(client_old == null) {
                throw new IllegalArgumentException("New clients may not have an id set");
            }

            if(!client_old.getName().equals(client.getName())) {
                if(!isProperClientName(client.getName())) {
                    throw new IllegalArgumentException("Client name must match pattern: " + NAME_PATTERN);
                }
                if(clientRepository.existsByName(client.getName())) {
                    throw new IllegalArgumentException("Client name must be unique");
                }
            }
        } else {
            if(!isProperClientName(client.getName())) {
                throw new IllegalArgumentException("Client name must match pattern: " + NAME_PATTERN);
            }
        }

        if(client.isDefaultClient()) {
            clientRepository.save(clientRepository.findByDefaultClientTrue().stream().map(
                    c -> {
                        c.setDefaultClient(false);
                        return c;
                    }
            ).collect(Collectors.toList()));
        }

        //create client with default config (if there is one)
        if(!configurationService.isConfiguration(client.getName())) {
            Client c = clientRepository.findOneByDefaultClientTrue();
            if(c != null) {
                Configuration c_conf = configurationService.getConfiguration(c.getName());
                if(c_conf != null) {
                    configurationService.createConfiguration(client.getName(),c_conf);
                } else {
                    configurationService.createConfiguration(client.getName());
                }
            } else {
                configurationService.createConfiguration(client.getName());
            }
        }

        client.setLastUpdate(new Date());

        return clientRepository.save(client);
    }

    public void delete(Client client) {
        configurationService.deleteConfiguration(client.getName());
        clientRepository.delete(client);
    }

    public boolean exists(ObjectId id) {
        return clientRepository.exists(id);
    }

    public boolean existsByName(String clientName) {
        return clientRepository.existsByName(clientName);
    }

    private static boolean isProperClientName(String name) {
        return name.matches(NAME_PATTERN);
    }
}
