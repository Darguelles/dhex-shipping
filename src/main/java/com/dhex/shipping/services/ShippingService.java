package com.dhex.shipping.services;

import com.dhex.shipping.exceptions.InvalidArgumentDhexException;
import com.dhex.shipping.exceptions.NotValidShippingStatusException;
import com.dhex.shipping.exceptions.ShippingNotFoundException;
import com.dhex.shipping.model.ShippingRequest;
import com.dhex.shipping.model.ShippingRequestTrack;
import com.dhex.shipping.model.ShippingStatus;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

public class ShippingService {
    private long sequenceId = 0;
    private List<ShippingRequest> shippingRequestList = new ArrayList<>();

    public ShippingRequest registerRequest(String receiverName, String senderName, String destinationAddress, long sendCost, String observations) {
        double totalCost;
        // Validate all the objects
        validateParameters(receiverName, senderName, destinationAddress, sendCost);
        double costWithComission;
        // According to the business rules, it considers a special commission for each range of amount.
        totalCost = getTotalCost(sendCost);

        String generatedId = generateId(senderName);
        ShippingRequest generatedShippingRequest = generateShippingRequest(receiverName, senderName, destinationAddress, observations, totalCost, generatedId);

        shippingRequestList.add(generatedShippingRequest);
        return generatedShippingRequest;
    }

    private ShippingRequest generateShippingRequest(String receiverName, String senderName, String destinationAddress, String observations, double totalCost, String generatedId) {
        ShippingRequest generatedShippingRequest = new ShippingRequest(
                generatedId, receiverName, senderName, destinationAddress, totalCost, OffsetDateTime.now());
        if(observations != null && !observations.isEmpty()) {
            generatedShippingRequest.setObservations(observations);
        }
        return generatedShippingRequest;
    }

    /* According to latest changes in the business, ID should be generated according to these rules:
       - First letter should be the sender name's registered name.
       - Following 6 letters should be the year (yyyy) and month (mm).
       - Finally, put the 16 digits of a sequential number.*/
    private String generateId(String senderName) {
        String generatedId = senderName.substring(0, 1);
        generatedId += String.valueOf(OffsetDateTime.now().getYear());
        generatedId += String.format("%02d", OffsetDateTime.now().getMonth().getValue());
        generatedId += String.format("%016d", ++sequenceId);
        return generatedId;
    }

    private double getTotalCost(long sendCost) {
        double costWithComission;
        double totalCost;
        if (sendCost < 20) {
            costWithComission = sendCost + 3;
        } else if (sendCost >= 20 && sendCost < 100) {
            costWithComission = sendCost + 8;
        } else if (sendCost >= 100 && sendCost < 300) {
            costWithComission = sendCost + 17;
        } else if (sendCost >= 300 && sendCost < 500) {
            costWithComission = sendCost + 20;
        } else if (sendCost >= 500 && sendCost < 1000) {
            costWithComission = sendCost + 50;
        } else if (sendCost >= 1000 && sendCost < 10000) {
            costWithComission = sendCost * 1.05;
        } else {
            costWithComission = sendCost * 1.03;
        }
        totalCost = costWithComission * 1.18;
        return totalCost;
    }

    private void validateParameters(String receiverName, String senderName, String destinationAddress, long sendCost) {

        validateParameter(receiverName, "Receiver should not be empty");
        validateParameter(senderName, "Sender should not be empty");
        validateParameter(destinationAddress, "Destination address should not be empty");

        if (sendCost < 0) {
            throw new InvalidArgumentDhexException("Sending cost should be positive");
        }
    }

    private void validateParameter(String parameterName, String parameterValue) {
        if (parameterName == null || parameterName.isEmpty())
            throw new InvalidArgumentDhexException(parameterValue);
    }

    public ShippingStatus registerStatus(String requestId, String location, String status, String observations) {

        ShippingRequest shippingRequest = findShippingRequest(requestId);
        ShippingStatus lastStatus = shippingRequest.getLastStatus();

        // Status can be changed from "In transit" or "Internal" to any other status (including "In transit").
        // Status can be changed only from "On hold" to "In transit".
        // Any other status cannot be changed.
        boolean isValidStatus = lastStatus != null;

        validateStatus(status, lastStatus, isValidStatus);

        String statusId = generateStatusId(shippingRequest);
        ShippingStatus shipStat = new ShippingStatus(statusId, location, status, OffsetDateTime.now(), observations);
        shippingRequest.addStatus(shipStat);
        return shipStat;
    }

    private void validateStatus(String status, ShippingStatus lastStatus, boolean isValidStatus) {
        if (isValidStatus) {
            boolean isNotInternal = !lastStatus.getStatus().equalsIgnoreCase("internal");
            boolean isNotInTransit = !lastStatus.getStatus().equalsIgnoreCase("in transit");
            if (isNotInternal && isNotInTransit) {
                throwExceptionIfNotValidStatus(status, lastStatus);
            }
        }
    }

    // According to the rules of the business, this ID should be conformed of:
    // - Prefix "S".
    // - Followed by the shipping request ID.
    // - Followed by a dash.
    // - And finally the 3 digits of a sequential number for all the statuses for that shipping request.
    private String generateStatusId(ShippingRequest shipReq) {
        return "S" + shipReq.getId() + "-" + String.format("%03d", shipReq.getStatusList().size() + 1);
    }

    private void throwExceptionIfNotValidStatus(String currentStatus, ShippingStatus lastStatus) {
        if (lastStatus.getStatus().equalsIgnoreCase("on hold") &&
                !currentStatus.equalsIgnoreCase("in transit") ||
                !lastStatus.getStatus().equalsIgnoreCase("on hold")) {
            throw new NotValidShippingStatusException(lastStatus.getStatus(), currentStatus);
        }
    }

    /* Search the shipping request that matches with request ID.
       Otherwise throws an exception.*/
    private ShippingRequest findShippingRequest(String reqId) {
        return shippingRequestList.stream()
                    .limit(1)
                    .filter(sr -> sr.getId().equals(reqId))
                    .findFirst()
                    .orElseThrow(() -> new ShippingNotFoundException(reqId));
    }

    public List<ShippingRequestTrack> trackStatusOf(String reqId) {
        // Search the shipping request that matches with request ID.
        // Otherwise throws an exception.
        ShippingRequest shipReq = findShippingRequest(reqId);

        LinkedList<ShippingRequestTrack> tracks = new LinkedList<>();
        // We have to return each status transformed into track
        for (ShippingStatus stat : shipReq.getStatusList()) {
            if(stat.getStatus().equalsIgnoreCase("internal")) {
                continue;
            } else {
                tracks.add(generateShippingRequestTrack(stat));
            }
        }
        return tracks;
    }

    private ShippingRequestTrack generateShippingRequestTrack(ShippingStatus stat) {
        return new ShippingRequestTrack(
                stat.getLocation(),
                stat.getMoment().format(DateTimeFormatter.ofPattern("MMM dd'th' 'of' yyyy")),
                stat.getStatus(),
                stat.getObservations());
    }
}
