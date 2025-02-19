package com.pesterenan.controllers;

import com.pesterenan.model.ActiveVessel;
import com.pesterenan.resources.Bundle;
import com.pesterenan.utils.ControlePID;
import com.pesterenan.utils.Modulos;
import com.pesterenan.utils.Navigation;
import com.pesterenan.views.MainGui;
import com.pesterenan.views.StatusJPanel;
import krpc.client.RPCException;
import krpc.client.Stream;
import krpc.client.StreamException;
import krpc.client.services.SpaceCenter.Engine;
import krpc.client.services.SpaceCenter.Node;
import krpc.client.services.SpaceCenter.Orbit;
import krpc.client.services.SpaceCenter.RCS;
import krpc.client.services.SpaceCenter.VesselSituation;
import org.javatuples.Triplet;

import java.util.List;
import java.util.Map;

public class ManeuverController extends ActiveVessel implements Runnable {

	private final ControlePID ctrlRCS = new ControlePID();
	private final ControlePID ctrlManeuver = new ControlePID();
	private final Navigation nav = new Navigation();
	private boolean fineAdjustment;

	public ManeuverController(Map<String, String> commands) {
		super(getConexao());
		this.commands = commands;
		initializeParameters();
	}

	private void initializeParameters() {
		try {
			ctrlRCS.adjustOutput(0.5, 1.0);
			currentBody = naveAtual.getOrbit().getBody();
			fineAdjustment = canFineAdjust(commands.get(Modulos.AJUSTE_FINO.get()));
			tuneAutoPilot();
		} catch (RPCException e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public void run() {
		calculateManeuver();
		executeNextManeuver();
	}

	public void calculateManeuver() {
		try {
			if (commands.get(Modulos.FUNCAO.get()).equals(Modulos.EXECUTAR.get())) {
				return;
			}
			if (naveAtual.getSituation() == VesselSituation.LANDED ||
					naveAtual.getSituation() == VesselSituation.SPLASHED) {
				throw new InterruptedException();
			}
			if (commands.get(Modulos.FUNCAO.get()).equals(Modulos.AJUSTAR.get())) {
				this.alignPlanes();
				return;
			}
			double parametroGravitacional = currentBody.getGravitationalParameter();
			double altitudeInicial = 0, tempoAteAltitude = 0;
			if (commands.get(Modulos.FUNCAO.get()).equals(Modulos.APOASTRO.get())) {
				altitudeInicial = naveAtual.getOrbit().getApoapsis();
				tempoAteAltitude = naveAtual.getOrbit().getTimeToApoapsis();
			}
			if (commands.get(Modulos.FUNCAO.get()).equals(Modulos.PERIASTRO.get())) {
				altitudeInicial = naveAtual.getOrbit().getPeriapsis();
				tempoAteAltitude = naveAtual.getOrbit().getTimeToPeriapsis();
			}

			double semiEixoMaior = naveAtual.getOrbit().getSemiMajorAxis();
			double velOrbitalAtual =
					Math.sqrt(parametroGravitacional * ((2.0 / altitudeInicial) - (1.0 / semiEixoMaior)));
			double velOrbitalAlvo =
					Math.sqrt(parametroGravitacional * ((2.0 / altitudeInicial) - (1.0 / altitudeInicial)));
			double deltaVdaManobra = velOrbitalAlvo - velOrbitalAtual;
			double[] deltaV = { deltaVdaManobra, 0, 0 };
			createManeuver(tempoAteAltitude, deltaV);
		} catch (RPCException | InterruptedException e) {
			disengageAfterException(Bundle.getString("status_maneuver_not_possible"));
		}
	}

	public void matchOrbitApoapsis() {
		try {
			Orbit targetOrbit = getTargetOrbit();
			System.out.println(targetOrbit.getApoapsis() + "-- APO");
			Node maneuver = hohmannTransferToOrbit(targetOrbit, naveAtual.getOrbit().getTimeToPeriapsis());
			while (true) {
				double currentDeltaApo = compareOrbitParameter(maneuver.getOrbit(), targetOrbit, Compare.AP);
				String deltaApoFormatted = String.format("%.2f", currentDeltaApo);
				System.out.println(deltaApoFormatted);
				if (deltaApoFormatted.equals(String.format("%.2f", 0.00))) {
					break;
				}
				double dvPrograde = maneuver.getPrograde();
				double ctrlOutput = ctrlManeuver.calcPID(currentDeltaApo, 0);

				maneuver.setPrograde(dvPrograde - (ctrlOutput));
				Thread.sleep(50);
			}
		} catch (Exception e) {
			disengageAfterException("Não foi possivel ajustar a inclinação");
		}
	}

	private Node hohmannTransferToOrbit(Orbit targetOrbit, double timeToStart) {
		double[] totalDv = { 0, 0, 0 };
		try {
			double startingRadius = naveAtual.getOrbit().getPeriapsis();
			double finalRadius = targetOrbit.getApoapsis();
			System.out.println(startingRadius + " --- " + finalRadius);
			double gravitationalParameter = currentBody.getGravitationalParameter();
			// DeltaV used to get to the second Node
			double firstNodeDv = Math.sqrt(gravitationalParameter * ((2.0 / startingRadius) -
					(1.0 / naveAtual.getOrbit().getSemiMajorAxis())));
			// DeltaV used to orbit in the second Node
			double secondNodeDv = Math.sqrt(gravitationalParameter * ((2.0 / startingRadius) - (1.0 / finalRadius)));
			// Time taken between the two points
			totalDv[0] = secondNodeDv - firstNodeDv;
		} catch (RPCException e) {
			e.printStackTrace();
		}
		return createManeuver(timeToStart, totalDv);
	}

	private Node createManeuverAtClosestIncNode(Orbit targetOrbit) {
		double uTatClosestNode = 1;
		double[] dv = { 0, 0, 0 };
		try {
			double[] incNodesUt = getTimeToIncNodes(targetOrbit);
			uTatClosestNode = Math.min(incNodesUt[0], incNodesUt[1]) - centroEspacial.getUT();
		} catch (Exception ignored) {
		}
		return createManeuver(uTatClosestNode, dv);
	}

	private double[] getTimeToIncNodes(Orbit targetOrbit) throws RPCException {
		Orbit vesselOrbit = naveAtual.getOrbit();
		double ascendingNode = vesselOrbit.trueAnomalyAtAN(targetOrbit);
		double descendingNode = vesselOrbit.trueAnomalyAtDN(targetOrbit);
		return new double[]{ vesselOrbit.uTAtTrueAnomaly(ascendingNode), vesselOrbit.uTAtTrueAnomaly(descendingNode) };
	}

	public void alignPlanes() {
		try {
			Orbit targetOrbit = getTargetOrbit();
			Node maneuver = createManeuverAtClosestIncNode(targetOrbit);
			double[] incNodesUt = getTimeToIncNodes(targetOrbit);
			boolean closestIsAN = incNodesUt[0] < incNodesUt[1];
			double timeToExecute = 0;
			while (timeToExecute < 5000) {
				double currentDeltaInc = compareOrbitParameter(maneuver.getOrbit(), targetOrbit, Compare.INC);
				String deltaIncFormatted = String.format("%.2f", currentDeltaInc);
				System.out.println(deltaIncFormatted);
				if (deltaIncFormatted.equals(String.format("%.2f", 10.00))) {
					break;
				}
				double dvNormal = maneuver.getNormal();
				double ctrlOutput = ctrlManeuver.calcPID(currentDeltaInc, 10.0);// * limitPIDOutput(Math.abs
				// (currentDeltaInc));
				if ((closestIsAN ? currentDeltaInc : -currentDeltaInc) > 0.0) {
					maneuver.setNormal(dvNormal + (ctrlOutput));
				} else {
					maneuver.setNormal(dvNormal - (ctrlOutput));
				}
				timeToExecute += 25;
				Thread.sleep(25);
			}
		} catch (Exception e) {
			disengageAfterException("Não foi possivel ajustar a inclinação");
		}
	}

	private double limitPIDOutput(double absDeltaInc) {
		if (absDeltaInc < 0.05) {
			return 0.1;
		}
		if (absDeltaInc < 0.5) {
			return 5;
		}
		return 10;
	}

	private double compareOrbitParameter(Orbit maneuverOrbit, Orbit targetOrbit, Compare parameter) {
		double maneuverParameter;
		double targetParameter;
		double delta = 0;
		try {
			switch (parameter) {
				case INC:
					maneuverParameter = maneuverOrbit.getInclination();
					System.out.println(maneuverParameter + " maneuver");
					targetParameter = targetOrbit.getInclination();
					System.out.println(targetParameter + " target");
					delta = (maneuverParameter / targetParameter) * 10;
					break;
				case AP:
					maneuverParameter = Math.round(maneuverOrbit.getApoapsis() / 100000.0);
					targetParameter = Math.round(targetOrbit.getApoapsis() / 100000.0);
					delta = (targetParameter - maneuverParameter);
					break;
				case PE:
					maneuverParameter = Math.round(maneuverOrbit.getPeriapsis()) / 100.0;
					targetParameter = Math.round(targetOrbit.getPeriapsis()) / 100.0;
					delta = (targetParameter - maneuverParameter);
					break;
				default:
					break;
			}

		} catch (RPCException e) {
			e.printStackTrace();
		}
		return delta;
	}

	private Orbit getTargetOrbit() throws RPCException {
		if (centroEspacial.getTargetBody() != null) {
			return centroEspacial.getTargetBody().getOrbit();
		}
		if (centroEspacial.getTargetVessel() != null) {
			return centroEspacial.getTargetVessel().getOrbit();
		}
		return null;
	}

	private Node createManeuver(double laterTime, double[] deltaV) {
		Node maneuverNode = null;
		try {
			naveAtual.getControl()
			         .addNode(centroEspacial.getUT() + laterTime, (float) deltaV[0], (float) deltaV[1],
			                  (float) deltaV[2]
			                 );
			List<Node> currentNodes = naveAtual.getControl().getNodes();
			maneuverNode = currentNodes.get(currentNodes.size() - 1);
		} catch (UnsupportedOperationException | RPCException e) {
			disengageAfterException(Bundle.getString("status_maneuver_not_possible"));
		}
		return maneuverNode;
	}

	public void executeNextManeuver() {
		try {
			List<Node> nodes = naveAtual.getControl().getNodes();
			Node maneuverNode = nodes.get(0);
			double burnTime = calculateBurnTime(maneuverNode);
			orientToManeuverNode(maneuverNode);
			executeBurn(maneuverNode, burnTime);
		} catch (UnsupportedOperationException e) {
			disengageAfterException(Bundle.getString("status_maneuver_not_unlocked"));
		} catch (IndexOutOfBoundsException e) {
			disengageAfterException(Bundle.getString("status_maneuver_unavailable"));
		} catch (RPCException e) {
			disengageAfterException(Bundle.getString("status_data_unavailable"));
		}
	}

	public void orientToManeuverNode(Node maneuverNode) {
		try {
			StatusJPanel.setStatus(Bundle.getString("status_orienting_ship"));
			ap.engage();
			ap.setTargetRoll(0);
			nav.targetManeuver(maneuverNode);
			System.out.println("iniciando rolagem");
			while (ap.getRollError() > 3) {
				ap.wait_();
			}
			System.out.println("iniciando miragem");
			while (ap.getError() > 3) {
				ap.wait_();
			}
			System.out.println("miragem terminada");
		} catch (RPCException e) {
			disengageAfterException(Bundle.getString("status_couldnt_orient"));
		}
	}

	public double calculateBurnTime(Node noDeManobra) throws RPCException {

		List<Engine> motores = naveAtual.getParts().getEngines();
		for (Engine motor : motores) {
			if (motor.getPart().getStage() == naveAtual.getControl().getCurrentStage() && !motor.getActive()) {
				motor.setActive(true);
			}
		}
		double empuxo = naveAtual.getAvailableThrust();
		double isp = naveAtual.getSpecificImpulse() * CONST_GRAV;
		double massaTotal = naveAtual.getMass();
		double massaSeca = massaTotal / Math.exp(noDeManobra.getDeltaV() / isp);
		double taxaDeQueima = empuxo / isp;
		double duracaoDaQueima = (massaTotal - massaSeca) / taxaDeQueima;

		StatusJPanel.setStatus("Tempo de Queima da Manobra: " + duracaoDaQueima + " segundos");
		return duracaoDaQueima;
	}

	public void executeBurn(Node noDeManobra, double duracaoDaQueima) {
		try {
			double inicioDaQueima = noDeManobra.getTimeTo() - (duracaoDaQueima / 2.0) - (fineAdjustment ? 5 : 0);
			StatusJPanel.setStatus(Bundle.getString("status_maneuver_warp"));
			if (inicioDaQueima > 30) {
				centroEspacial.warpTo((centroEspacial.getUT() + inicioDaQueima - 10), 100000, 4);
			}
			// Mostrar tempo de ignição:
			StatusJPanel.setStatus(String.format(Bundle.getString("status_maneuver_duration"), duracaoDaQueima));
			while (inicioDaQueima > 0) {
				inicioDaQueima = noDeManobra.getTimeTo() - (duracaoDaQueima / 2.0);
				inicioDaQueima = Math.max(inicioDaQueima, 0.0);
				nav.targetManeuver(noDeManobra);
				StatusJPanel.setStatus(String.format(Bundle.getString("status_maneuver_ignition_in"), inicioDaQueima));
				Thread.sleep(100);
			}
			// Executar a manobra:
			Stream<Triplet<Double, Double, Double>> queimaRestante =
					getConexao().addStream(noDeManobra, "remainingBurnVector", noDeManobra.getReferenceFrame());
			StatusJPanel.setStatus(Bundle.getString("status_maneuver_executing"));
			double limiteParaDesacelerar =
					noDeManobra.getDeltaV() > 1000 ? 0.025 : noDeManobra.getDeltaV() > 250 ? 0.10 : 0.25;

			while (!noDeManobra.equals(null)) {
				if (queimaRestante.get().getValue1() < (fineAdjustment ? 2 : 0.5)) {
					break;
				}
				nav.targetManeuver(noDeManobra);
				throttle(ctrlManeuver.calcPID(
						(noDeManobra.getDeltaV() - queimaRestante.get().getValue1()) / noDeManobra.getDeltaV() * 1000,
						1000
				                             ));
				MainGui.getParametros()
				       .getComponent(0)
				       .firePropertyChange("distancia", 0, queimaRestante.get().getValue1());
				Thread.sleep(25);
			}
			throttle(0.0f);
			if (fineAdjustment) {
				adjustManeuverWithRCS(queimaRestante);
			}
			ap.setReferenceFrame(pontoRefSuperficie);
			ap.disengage();
			naveAtual.getControl().setSAS(true);
			naveAtual.getControl().setRCS(false);
			queimaRestante.remove();
			noDeManobra.remove();
			StatusJPanel.setStatus(Bundle.getString("status_ready"));
		} catch (StreamException | RPCException e) {
			disengageAfterException(Bundle.getString("status_data_unavailable"));
		} catch (InterruptedException e) {
			disengageAfterException(Bundle.getString("status_maneuver_cancelled"));
		}
	}

	private void adjustManeuverWithRCS(Stream<Triplet<Double, Double, Double>> remainingDeltaV) throws RPCException,
			StreamException, InterruptedException {
		naveAtual.getControl().setRCS(true);
		while (Math.floor(remainingDeltaV.get().getValue1()) > 0.2) {
			naveAtual.getControl().setForward((float) ctrlRCS.calcPID(-remainingDeltaV.get().getValue1() * 10, 0));
			Thread.sleep(25);
		}
		naveAtual.getControl().setForward(0);
	}

	private boolean canFineAdjust(String string) {
		if (string.equals("true")) {
			try {
				List<RCS> rcsEngines = naveAtual.getParts().getRCS();
				if (rcsEngines.size() > 0) {
					for (RCS rcs : rcsEngines) {
						if (rcs.getHasFuel()) {
							return true;
						}
					}
				}
				return false;
			} catch (RPCException ignored) {
			}
		}
		return false;
	}

	enum Compare {
		INC, AP, PE
	}

}